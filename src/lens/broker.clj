(ns lens.broker
  (:use plumbing.core)
  (:require [clojure.core.async :as async :refer [<!! >!!]]
            [schema.core :refer [Keyword Any Uuid Int Str]]
            [lens.logging :as log :refer [error debug]]
            [lens.util :refer [NonBlankStr]]
            [lens.common :refer [Command Event]]
            [langohr.core :as rmq]
            [langohr.channel :as ch]
            [langohr.queue :as qu]
            [langohr.exchange :as ex]
            [langohr.basic :as lb]
            [langohr.consumers :as co]
            [com.stuartsierra.component :refer [Lifecycle]]
            [cognitect.transit :as t]
            [clojure.java.io :as io]
            [schema.core :as s])
  (:import [java.io ByteArrayOutputStream]
           [com.rabbitmq.client Channel]))

(set! *warn-on-reflection* true)

;; ---- Commands --------------------------------------------------------------

(defn- read-command [payload]
  (try
    (t/read (t/reader (io/input-stream payload) :msgpack))
    (catch Exception e e)))

(defn delivery-fn [command-ch]
  (fn [^Channel ch {:keys [delivery-tag]} payload]
    (try
      (let [command (assoc (read-command payload) :delivery-tag delivery-tag)]
        (debug {:command command})
        (>!! command-ch command))
      (catch Throwable t
        (error t {:type :unreadable-command
                  :ch-num (.getChannelNumber ch)
                  :delivery-tag delivery-tag
                  :error (.getMessage t)})
        (lb/reject ch delivery-tag)))))

(defn- write [o]
  (let [out (ByteArrayOutputStream.)]
    (t/write (t/writer out :msgpack) o)
    (.toByteArray out)))

;; ---- Events ----------------------------------------------------------------

(s/defn event-routing-key [event-name :- Keyword]
  (if-let [ns (namespace event-name)]
    (str ns "." (name event-name))
    (name event-name)))

(defn send-event [ch exchange {:keys [name] :as event}]
  (lb/publish ch exchange (event-routing-key name) (write event)))

(defn events-loop [ch exchange event-ch]
  (async/thread
    (loop []
      (when-letk [[type delivery-tag :as event] (<!! event-ch)]
        (case type
          :success
          (do (send-event ch exchange (dissoc event :type :delivery-tag))
              (lb/ack ch delivery-tag))
          :error
          (do (send-event ch exchange (dissoc event :type :delivery-tag))
              (lb/reject ch delivery-tag)
              (error event)))
        (recur)))))

;; ---- Broker ----------------------------------------------------------------

(defn- info [msg]
  (log/info {:component "Broker" :msg msg}))

(defn- subscribe [ch queue f consumer-tag]
  (info (format "Subscribe to queue %s with consumer tag %s" queue consumer-tag))
  (co/subscribe ch queue f {:consumer-tag consumer-tag}))

(defrecord Broker [host port username password command-queue exchange
                   conn command-prefetch-count command-ch event-ch]
  Lifecycle
  (start [broker]
    (info (format "Start broker on command queue %s and topic exchange %s."
                  command-queue exchange))
    (let [opts (cond-> {}
                 host (assoc :host host)
                 port (assoc :port port)
                 username (assoc :username username)
                 password (assoc :password password))
          conn (rmq/connect opts)
          command-queue-ch (ch/open conn)]
      (ex/declare command-queue-ch exchange "topic" {:durable true})
      (qu/declare command-queue-ch command-queue {:durable true :auto-delete false})
      (lb/qos command-queue-ch command-prefetch-count)
      (let [command-ch (async/chan 1)
            event-ch (async/chan 1)]
        (events-loop command-queue-ch exchange event-ch)
        (subscribe command-queue-ch command-queue (delivery-fn command-ch) "lens-sds-tx")
        (assoc broker :conn conn :command-ch command-ch :event-ch event-ch))))

  (stop [broker]
    (info "Stop broker")
    (async/close! command-ch)
    (async/close! event-ch)
    (rmq/close conn)
    (assoc broker :conn nil :command-ch nil :event-ch nil)))

(defn new-broker [opts]
  (-> opts
      (assoc :command-queue "lens-sds.commands")
      (assoc :exchange "lens-sds.events")
      (assoc :command-prefetch-count 64)
      (map->Broker)))
