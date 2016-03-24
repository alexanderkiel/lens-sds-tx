(ns lens.handlers.study-event
  "Command handlers for the study-event aggregate."
  (:require [clj-uuid :as uuid]
            [datomic.api :refer [tempid squuid]]
            [lens.handlers.core :refer [defcommand resolve-entity Entity]]
            [lens.util :refer [NonBlankStr]]
            [schema.core :as s :refer [Str]]))

(def StudyEvent
  (s/constrained Entity :study-event/id 'study-event?))

(defn form-id [study-event form-oid]
  (uuid/v5 (:study-event/id study-event) form-oid))

(defcommand create-form
  {:aliases [:odm-import/insert-form]
   :agg [:study-event/id :study-event-id]}
  (s/fn [_ study-event :- StudyEvent _ {:keys [form-oid]}]
    (s/validate NonBlankStr form-oid)
    [{:db/id (tempid :forms -1)
      :agg/version 0
      :form/id (form-id study-event form-oid)
      :form/oid form-oid}
     [:db/add (:db/id study-event) :study-event/forms (tempid :forms -1)]
     [:event.fn/create :form/created]]))

(defcommand odm-import/remove-form
  {:agg [:study-event/id :study-event-id]}
  (s/fn [_ study-event :- StudyEvent _ {:keys [form-oid]}]
    (let [form-ref [:form/id (form-id study-event form-oid)]]
      [[:db.fn/retractEntity form-ref]
       [:db/retract (:db/id study-event) :study-event/forms form-ref]
       [:event.fn/create :form/removed]])))
