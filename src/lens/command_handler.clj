(ns lens.command-handler
  (:use plumbing.core)
  (:require [lens.broker :refer [handle-command send-event]]
            [lens.logging :refer [debug]]
            [schema.core :as s :refer [Str Keyword]]
            [datomic.api :as d]
            [clj-uuid :as uuid])
  (:import [java.util.concurrent ExecutionException]))

(set! *warn-on-reflection* true)

(defn resolve-tempid [tid tx-result]
  (d/resolve-tempid (:db-after tx-result) (:tempids tx-result) tid))

(defn- success-event [tid id-attr tx-result]
  (let [db (:db-after tx-result)
        t (d/basis-t db)
        tx (d/entity db (d/t->tx t))]
    {:id (:event/id tx)
     :cid (:event/cid tx)
     :name (:event/name tx)
     :sub (:event/sub tx)
     :t t
     :data {:id (id-attr (d/entity db (resolve-tempid tid tx-result)))}}))

(defn transaction-timeout-ex [timeout-ms]
  (ex-info "Transaction timeout."
           {:type ::transaction-timeout
            :timeout-ms timeout-ms}))

(defn- transact-with-timeout [conn tid id-attr tx-data timeout-ms]
  (if-let [tx-result (deref (d/transact-async conn tx-data) timeout-ms nil)]
    (success-event tid id-attr tx-result)
    (throw (transaction-timeout-ex timeout-ms))))

(defn- error-event [^Exception e {:keys [id] :as cmd}]
  (-> {:id (uuid/v5 id ::transaction-failed)
       :cid id
       :name ::transaction-failed
       :sub (:sub cmd)
       :data
       (-> {:error-msg (.getMessage e)}
           (assoc-when :ex-data (ex-data e)))}))

(defn add-event
  "Adds event with name to transaction data. The id is build from the command
  id using a v5 namespaced UUID."
  {:arglists '([tx-data name cmd])}
  [tx-data name {:keys [id sub]}]
  (conj tx-data [:event.fn/create (uuid/v5 id name) id name sub]))

(s/defn transact [db-uri :- Str tid id-attr tx-data
                  success-event-name :- Keyword cmd]
  (try
    (transact-with-timeout (d/connect db-uri) tid id-attr
                           (add-event tx-data success-event-name cmd)
                           10000)
    (catch ExecutionException e
      (error-event (.getCause e) cmd))
    (catch Exception e
      (error-event e cmd))))

;; ---- Create Study ----------------------------------------------------------

(defmethod handle-command :create-study
  [{:keys [broker db-uri]} cmd {:keys [study-oid]}]
  (let [tid (d/tempid :studies)
        tx-data [[:study.fn/create tid study-oid]]
        event (transact db-uri tid :study/oid tx-data :study/created cmd)]
    (send-event broker event)))

;; ---- Create Subject --------------------------------------------------------

(defmethod handle-command :create-subject
  [{:keys [broker db-uri]} cmd {:keys [study-oid subject-key]}]
  (let [tid (d/tempid :subjects)
        tx-data [[:subject.fn/create tid study-oid subject-key]]
        event (transact db-uri tid :subject/id tx-data :subject/created cmd)]
    (send-event broker event)))

;; ---- Create Study Event ----------------------------------------------------

(defmethod handle-command :create-study-event
  [{:keys [broker db-uri]} cmd {:keys [subject-id study-event-oid]}]
  (let [tid (d/tempid :study-events)
        tx-data [[:study-event.fn/create tid subject-id study-event-oid]]
        event (transact db-uri tid :study-event/id tx-data :study-event/created cmd)]
    (send-event broker event)))

;; ---- Create Form -----------------------------------------------------------

(defmethod handle-command :create-form
  [{:keys [broker db-uri]} cmd {:keys [study-event-id form-oid]}]
  (let [tid (d/tempid :forms)
        tx-data [[:form.fn/create tid study-event-id form-oid]]
        event (transact db-uri tid :form/id tx-data :form/created cmd)]
    (send-event broker event)))

;; ---- Create Item Group -----------------------------------------------------

(defmethod handle-command :create-item-group
  [{:keys [broker db-uri]} cmd {:keys [form-id item-group-oid]}]
  (let [tid (d/tempid :item-groups)
        tx-data [[:item-group.fn/create tid form-id item-group-oid]]
        event (transact db-uri tid :item-group/id tx-data :item-group/created cmd)]
    (send-event broker event)))

;; ---- Create Item -----------------------------------------------------------

(defmethod handle-command :create-item
  [{:keys [broker db-uri]} cmd {:keys [item-group-id item-oid data-type value]}]
  (let [tid (d/tempid :items)
        tx-data [[:item.fn/create tid item-group-id item-oid data-type value]]
        event (transact db-uri tid :item/id tx-data :item/created cmd)]
    (send-event broker event)))
