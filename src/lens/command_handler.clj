(ns lens.command-handler
  (:require [clojure.core.async :as async :refer [go-loop <! >!]]
            [clojure.core.cache :as cache]
            [com.stuartsierra.component :refer [Lifecycle]]
            [datomic.api :as d]
            [lens.logging :as log :refer [debug trace warn]]
            [lens.util :as u :refer [NonNegInt]]
            [lens.handlers.core :refer [get-command get-agg-id-attr]]
            [lens.cache :as lc]
            [lens.common :refer [Command]]
            [lens.handlers.study]
            [lens.handlers.subject]
            [lens.handlers.study-event]
            [lens.handlers.form]
            [lens.handlers.item-group]
            [lens.handlers.item]
            [schema.core :as s :refer [Keyword]]
            [clj-uuid :as uuid]))

(def EId
  (s/named NonNegInt "eid"))

(def T
  (s/named NonNegInt "t"))

(defn perform-command [state {:keys [id name sub params] :as command}]
  (try
    (if-let [perform-command (get-command name)]
      (-> (perform-command state command params)
          (conj [:cmd.fn/create id name sub]))
      (Exception. (str "Missing perform command handler for " name)))
    (catch Exception e e)))

(s/defn event-names [db tx :- EId]
  (d/q '[:find [?n ...]
         :in $ ?tx
         :where [?e :event/tx ?tx] [?e :event/name ?n]]
       db tx))

(s/defn event [command :- Command t :- T event-name :- Keyword]
  {:type :success
   :id (uuid/v5 (:id command) event-name)
   :cid (:id command)
   :name event-name
   :sub (:sub command)
   :delivery-tag (:delivery-tag command)
   :t t})

(defn derive-events [command {db :db-after}]
  (for [event-name (event-names db (d/t->tx (d/basis-t db)))]
    (event command (d/basis-t db) event-name)))

(defn- error-event [command error]
  {:type :error
   :id (uuid/v5 (:id command) :error)
   :cid (:id command)
   :name :error
   :sub (:sub command)
   :delivery-tag (:delivery-tag command)
   :error (.getMessage error)})

(defn- group-by-state [txs]
  (group-by #(realized? (first %)) txs))

(defn- ports [running n ch]
  (cond-> [(async/timeout 1)]
    (> n (count running)) (conj ch)))

(defn transact-loop
  ""
  [conn n ch]
  (debug "Start transact looping...")
  (go-loop [running-txs nil
            finish? false]
    (trace {:loop :transact-loop :num-tx-in-flight (count running-txs)})
    (let [{finished true running false} (group-by-state running-txs)]
      (doseq [[res ch] finished]
        (let [res (u/deref-tx-res res)]
          (trace {:loop :transact-loop :action :push-back-res :res res})
          (>! ch res)))
      (cond
        (seq running)
        (let [[val port] (async/alts! (ports running n ch))]
          (if (= ch port)
            (if-let [[tx-data ch] val]
              (let [tx (d/transact-async conn tx-data)]
                (trace {:loop :transact-loop :action :transact :tx-data tx-data})
                (recur (conj running [tx ch]) false))
              (recur running true))
            (recur running finish?)))
        (not finish?)
        (if-let [[tx-data ch] (<! ch)]
          (let [tx (d/transact-async conn tx-data)]
            (trace {:loop :transact-loop :action :transact :tx-data tx-data})
            (recur (conj running [tx ch]) false))
          (recur running true))
        :else
        (debug "Finish transact looping.")))))

(defn db-loop [conn command-chan transact-ch event-ch]
  "Processes commands from command-ch in the context of the whole database in
  order and puts the results on event-ch. Contrast this with the aggregate
  loop which processed commands in the context of a single aggregate."
  (debug "Start whole database looping...")
  (go-loop []
    (if-let [command (<! command-chan)]
      (let [_ (trace {:loop :db-loop :command command})
            tx-data (perform-command (d/db conn) command)]
        (if (instance? Throwable tx-data)
          (do (trace {:loop :db-loop :error (.getMessage tx-data)})
              (>! event-ch (error-event command tx-data))
              (recur))
          (let [_ (trace {:loop :db-loop :tx-data tx-data})
                res-ch (async/promise-chan)
                _ (>! transact-ch [tx-data res-ch])
                res (<! res-ch)]
            (trace {:loop :db-loop :res res})
            (if (instance? Throwable res)
              (>! event-ch (error-event command res))
              (doseq [event (derive-events command res)]
                (>! event-ch event)))
            (recur))))
      (debug "Finish whole database looping."))))

(s/defn aggregate-loop
  "Processes commands from command-ch on aggregate with agg-id in order and
  puts the events on event-ch."
  [conn agg-id :- EId command-ch transact-ch event-ch]
  (debug (format "Start aggregate looping on %s..." agg-id))
  (go-loop []
    (if-let [command (<! command-ch)]
      (let [_ (trace {:loop [:aggregate-loop agg-id] :command command})
            tx-data (perform-command (d/entity (d/db conn) agg-id) command)]
        (if (instance? Throwable tx-data)
          (do (trace {:loop [:aggregate-loop agg-id] :error (.getMessage tx-data)})
              (>! event-ch (error-event command tx-data))
              (recur))
          (let [_ (trace {:loop [:aggregate-loop agg-id] :tx-data tx-data})
                res-ch (async/promise-chan)
                _ (>! transact-ch [tx-data res-ch])
                res (<! res-ch)]
            (trace {:loop [:aggregate-loop agg-id] :res res})
            (if (instance? Throwable res)
              (do (>! event-ch (error-event command res))
                  (recur))
              (do (doseq [event (derive-events command res)]
                    (>! event-ch event))
                  (recur))))))
      (debug (format "Finish aggregate looping on %s." agg-id)))))

(defn agg-not-found [lookup-ref]
  (Exception. (format "Aggregate with id %s not found." lookup-ref)))

(defn aid-but-no-agg-command [{:keys [name aid]}]
  (Exception. (format "Invalid command %s with aggregate id %s." name aid)))

(defn- agg-chan-cache [threshold]
  (lc/closing-lru-cache-factory {} :threshold threshold :close-fn async/close!))

(s/defn find-events
  "Finds already existing events of the command. Used for deduplication."
  [db {:keys [id] :as command} :- Command]
  (->> (d/q '[:find ?tx ?en
              :in $ ?cid
              :where [?tx :cmd/id ?cid] [?e :event/tx ?tx] [?e :event/name ?en]]
            db id)
       (map (fn [[tx event-name]] (event command (d/tx->t tx) event-name)))
       (seq)))

(defn command-loop [{:keys [conn]} {:keys [command-ch event-ch]}]
  (debug "Start command looping...")
  (let [transact-ch (async/chan 64)
        db-chan (async/chan 8)]
    (transact-loop conn 32 transact-ch)
    (db-loop conn db-chan transact-ch event-ch)
    (go-loop [agg-chans (agg-chan-cache 512)]
      (if-let [{:keys [aid name] :as command} (<! command-ch)]
        (let [db (d/db conn)]
          (trace {:loop :command-loop :command command})
          (if-let [events (find-events db command)]
            (do (doseq [event events]
                  (>! event-ch event))
                (recur agg-chans))
            (if aid
              (if-let [id-attr (get-agg-id-attr name)]
                (if-let [agg-id (:db/id (d/entity db [id-attr aid]))]
                  (if-let [agg-chan (cache/lookup agg-chans agg-id)]
                    (do (>! agg-chan command)
                        (recur (cache/hit agg-chans agg-id)))
                    (let [agg-chan (async/chan 4)]
                      (aggregate-loop conn agg-id agg-chan transact-ch event-ch)
                      (>! agg-chan command)
                      (recur (cache/miss agg-chans agg-id agg-chan))))
                  (do (>! event-ch (error-event command (agg-not-found [id-attr aid])))
                      (recur agg-chans)))
                (do (>! event-ch (error-event command (aid-but-no-agg-command command)))
                    (recur agg-chans)))
              (do (>! db-chan command) (recur agg-chans)))))
        (debug "Finished command looping.")))))

(defn- info [msg]
  (log/info {:component "CommandHandler" :msg msg}))

(defrecord CommandHandler [db-creator broker]
  Lifecycle
  (start [handler]
    (info "Start command handler")
    (command-loop db-creator broker)
    handler)
  (stop [handler]
    (info "Stop command handler")
    (async/close! (:command-ch broker))
    handler))

(defn new-command-handler []
  (map->CommandHandler {}))
