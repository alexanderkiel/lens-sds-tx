(ns lens.handlers.study-event
  (:require [clj-uuid :as uuid]
            [datomic.api :refer [tempid squuid]]
            [lens.handlers.core :refer [defcommand resolve-entity Entity]]
            [lens.handlers.subject :refer [Subject]]
            [schema.core :as s :refer [Str]]))

(def StudyEvent
  (s/constrained Entity :study-event/id 'study-event?))

(defn- find-study-event [subject study-event-oid]
  (some #(when (= study-event-oid (:study-event/oid %)) %)
        (:subject/study-events subject)))

(defn- duplicate [subject study-event-oid]
  (Exception. (str "The subject " (:subject/id subject) " contains already "
                   "a study-event with OID " study-event-oid ".")))

(defcommand create-study-event
  {:aliases [:odm-import/insert-study-event]
   :agg-id-attr :subject/id}
  (s/fn [subject :- Subject _ {:keys [study-event-oid]}]
    (s/validate Str study-event-oid)
    (when (find-study-event subject study-event-oid)
      (throw (duplicate subject study-event-oid)))
    [{:db/id (tempid :study-events -1)
      :agg/version 0
      :study-event/id (uuid/v5 (:subject/id subject) study-event-oid)
      :study-event/oid study-event-oid}
     [:db/add (:db/id subject) :subject/study-events (tempid :study-events -1)]
     [:event.fn/create :study-event/created]]))

(defcommand odm-import/upsert-study-event
  {:agg-id-attr :subject/id}
  (s/fn [subject :- Subject _ {:keys [study-event-oid]}]
    (s/validate Str study-event-oid)
    (if-let [study-event (find-study-event subject study-event-oid)]
      [[:agg.fn/inc-version (:db/id study-event) (:agg/version study-event)]
       [:event.fn/create :study-event/updated]]
      [{:db/id (tempid :study-events -1)
        :agg/version 0
        :study-event/id (uuid/v5 (:subject/id subject) study-event-oid)
        :study-event/oid study-event-oid}
       [:db/add (:db/id subject) :subject/study-events (tempid :study-events -1)]
       [:event.fn/create :study-event/created]])))
