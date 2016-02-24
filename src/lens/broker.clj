(ns lens.broker
  (:use plumbing.core)
  (:require [schema.core :as s :refer [Keyword Any Uuid Int Str]]
            [lens.logging :as log :refer [error debug]]
            [lens.util :refer [NonBlankStr]]
            [langohr.core :as rmq]
            [langohr.channel :as ch]
            [langohr.exchange :as ex]
            [langohr.queue :as qu]
            [langohr.basic :as lb]
            [langohr.consumers :as co]
            [com.stuartsierra.component :refer [Lifecycle]]
            [cognitect.transit :as t]
            [clojure.java.io :as io]
            [clj-uuid :as uuid])
  (:import [java.io ByteArrayOutputStream]
           [java.util.concurrent Executors]
           [com.rabbitmq.client Channel]))

(set! *warn-on-reflection* true)

;; ---- Schemas ---------------------------------------------------------------

(def Params
  {Any Any})

(def Command
  "A command is something which a subject likes to do in a system.

  A command has a id which is an arbitrary UUID. The name of a command is a
  keyword like :create-subject."
  {:id Uuid
   :name Keyword
   (s/optional-key :params) Params
   :sub NonBlankStr})

(def Event
  "An event is something which happend in a system.

  An event has an id which is an arbitrary UUID. The name of an event is a
  possibly namespaced keyword, which is used as topic in the lens-sds.events
  topic exchange. The name should be a verb in past tense like :subject/created."
  {:id Uuid
   :cid Uuid
   :name Keyword
   :sub NonBlankStr
   (s/optional-key :t) Int
   (s/optional-key :data) {Any Any}})

;; ---- Commands --------------------------------------------------------------

(defmulti handle-command (fn [_ {:keys [name]} _] name))

(defn- read-command [payload]
  (try
    (t/read (t/reader (io/input-stream payload) :msgpack))
    (catch Exception e e)))

(defn requeue? [e]
  (if (:requeue (ex-data e)) true false))

(defn delivery-fn [env]
  (fn [^Channel ch {:keys [message-id delivery-tag]} payload]
    (let [cmd (read-command payload)]
      (if (instance? Throwable cmd)
        (error cmd {:type :unreadable-command
                    :ch-num (.getChannelNumber ch)
                    :delivery-tag delivery-tag
                    :message-id message-id
                    :error (.getMessage ^Throwable cmd)})
        (if-let [e (s/check Command cmd)]
          (do (error {:type :invalid-command
                      :ch-num (.getChannelNumber ch)
                      :delivery-tag delivery-tag
                      :message-id message-id
                      :cmd cmd
                      :error e})
              (lb/reject ch delivery-tag))
          (do (debug {:ch-num (.getChannelNumber ch)
                      :delivery-tag delivery-tag
                      :message-id message-id
                      :cmd cmd})
              (try
                (handle-command env cmd (:params cmd))
                (lb/ack ch delivery-tag)
                (catch Exception e
                  (error e {:type :command-handling-error
                            :ch-num (.getChannelNumber ch)
                            :delivery-tag delivery-tag
                            :message-id message-id
                            :cmd cmd
                            :error (.getMessage e)})
                  (lb/reject ch delivery-tag (requeue? e))))))))))

(defn- write [o]
  (let [out (ByteArrayOutputStream.)]
    (t/write (t/writer out :msgpack) o)
    (.toByteArray out)))

;; ---- Events ----------------------------------------------------------------

(defn event-routing-key [event-name]
  (if-let [ns (namespace event-name)]
    (str ns "." (name event-name))
    (name event-name)))

(s/defn send-event
  "Sends event to broker."
  {:arglists '([broker event])}
  [{:keys [ch exchange]} {:keys [name] :as event} :- Event]
  (debug {:event event})
  (lb/publish ch exchange (event-routing-key name) (write event)))

;; ---- Broker ----------------------------------------------------------------

(defn- info [msg]
  (log/info {:component "Broker" :msg msg}))

(defn- subscribe [ch queue f consumer-tag]
  (info (format "Subscribe to queue %s with consumer tag %s" queue consumer-tag))
  (co/subscribe ch queue f {:consumer-tag consumer-tag}))

(defn- consumer-tag [^Channel ch]
  (str "lens-sds-tx." (.getChannelNumber ch)))

(defn open-channels [conn prefetch-count n]
  (for [_ (range n)]
    (let [ch (ch/open conn)]
      (lb/qos ch prefetch-count)
      ch)))

(defrecord Broker [host port username password conn ch chs queue exchange
                   num-threads db-uri]
  Lifecycle
  (start [broker]
    (info (format "Start broker on command queue %s and topic exchange %s."
                  queue exchange))
    (let [opts (cond-> {:executor (Executors/newFixedThreadPool num-threads)}
                 host (assoc :host host)
                 port (assoc :port port)
                 username (assoc :username username)
                 password (assoc :password password))
          conn (rmq/connect opts)
          ch (ch/open conn)
          chs (open-channels conn 32 num-threads)]
      (ex/declare ch exchange "topic" {:durable true})
      (qu/declare ch queue {:durable true :auto-delete false})
      (let [env {:broker (assoc broker :ch ch :exchange exchange)
                 :db-uri db-uri}]
        (doseq [ch chs]
          (subscribe ch queue (delivery-fn env) (consumer-tag ch))))
      (assoc broker :conn conn :chs chs)))

  (stop [broker]
    (info "Stop broker")
    (doseq [ch chs]
      (rmq/close ch))
    (when conn (rmq/close conn))
    (assoc broker :ch nil :chs nil :conn nil)))

(defn new-broker [opts]
  (cond-> opts
    true (assoc :queue "lens-sds.commands")
    true (assoc :exchange "lens-sds.events")
    (not (:num-threads opts)) (assoc :num-threads 4)
    true (map->Broker)))
