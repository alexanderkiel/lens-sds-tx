(ns lens.handlers.subject
  "Command handlers for the subject aggregate."
  (:require [clj-uuid :as uuid]
            [datomic.api :as d :refer [tempid]]
            [lens.handlers.core :refer [defcommand resolve-entity Entity]]
            [lens.util :refer [NonBlankStr]]
            [schema.core :as s :refer [Str]]))

(def Subject
  (s/constrained Entity :subject/id 'subject?))

(defn- study-event-id [subject study-event-oid]
  (uuid/v5 (:subject/id subject) study-event-oid))

(defcommand create-study-event
  {:aliases [:odm-import/insert-study-event]
   :agg [:subject/id :subject-id]}
  (s/fn [_ subject :- Subject _ {:keys [study-event-oid]}]
    (s/validate NonBlankStr study-event-oid)
    [{:db/id (tempid :study-events -1)
      :agg/version 0
      :study-event/id (study-event-id subject study-event-oid)
      :study-event/oid study-event-oid}
     [:db/add (:db/id subject) :subject/study-events (tempid :study-events -1)]
     [:event.fn/create :study-event/created]]))

(defcommand odm-import/upsert-study-event
  {:agg [:subject/id :subject-id]}
  (s/fn [db subject :- Subject _ {:keys [study-event-oid]}]
    (s/validate NonBlankStr study-event-oid)
    (if-let [study-event (d/entity db [:study-event/id (study-event-id subject study-event-oid)])]
      [[:agg.fn/inc-version (:db/id study-event) (:agg/version study-event)]
       [:event.fn/create :study-event/updated]]
      [{:db/id (tempid :study-events -1)
        :agg/version 0
        :study-event/id (study-event-id subject study-event-oid)
        :study-event/oid study-event-oid}
       [:db/add (:db/id subject) :subject/study-events (tempid :study-events -1)]
       [:event.fn/create :study-event/created]])))

(defcommand odm-import/remove-study-event
  {:agg [:subject/id :subject-id]}
  (s/fn [_ subject :- Subject _ {:keys [study-event-oid]}]
    (let [study-event-ref [:study-event/id (study-event-id subject study-event-oid)]]
      [[:db.fn/retractEntity study-event-ref]
       [:db/retract (:db/id subject) :subject/study-events study-event-ref]
       [:event.fn/create :study-event/removed]])))
