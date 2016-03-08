(ns lens.handlers.study-event
  (:require [clj-uuid :as uuid]
            [datomic.api :as d :refer [tempid squuid]]
            [lens.handlers.core :refer [defcommand resolve-entity Entity]]
            [lens.handlers.subject :refer [Subject]]
            [schema.core :as s :refer [Str]]))

(def StudyEvent
  (s/constrained Entity :study-event/id 'study-event?))

(defcommand create-study-event
  {:aliases [:odm-import/insert-study-event]
   :agg-id-attr :subject/id}
  (s/fn [_ subject :- Subject _ {:keys [study-event-oid]}]
    (s/validate Str study-event-oid)
    [{:db/id (tempid :study-events -1)
      :agg/version 0
      :study-event/id (uuid/v5 (:subject/id subject) study-event-oid)
      :study-event/oid study-event-oid}
     [:db/add (:db/id subject) :subject/study-events (tempid :study-events -1)]
     [:event.fn/create :study-event/created]]))

(defcommand odm-import/upsert-study-event
  {:agg-id-attr :subject/id}
  (s/fn [db subject :- Subject _ {:keys [study-event-oid]}]
    (s/validate Str study-event-oid)
    (let [study-event-id (uuid/v5 (:subject/id subject) study-event-oid)]
      (if-let [study-event (d/entity db [:study-event/id study-event-id])]
        [[:agg.fn/inc-version (:db/id study-event) (:agg/version study-event)]
         [:event.fn/create :study-event/updated]]
        [{:db/id (tempid :study-events -1)
          :agg/version 0
          :study-event/id study-event-id
          :study-event/oid study-event-oid}
         [:db/add (:db/id subject) :subject/study-events (tempid :study-events -1)]
         [:event.fn/create :study-event/created]]))))
