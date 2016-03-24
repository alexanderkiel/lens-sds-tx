(ns lens.handlers.study
  "Command handlers for the study aggregate."
  (:require [datomic.api :as d :refer [tempid]]
            [lens.handlers.core :refer [defcommand Entity]]
            [lens.util :refer [NonBlankStr]]
            [schema.core :as s :refer [Str]]
            [clj-uuid :as uuid]))

(def Study
  (s/constrained Entity :study/id 'study?))

(defn- subject-id [study subject-key]
  (uuid/v5 (:study/id study) subject-key))

(defcommand create-subject
  "Creates a subject in study using subject-key."
  {:aliases [:odm-import/insert-subject]
   :agg [:study/id :study-id]}
  (s/fn [_ study :- Study _ {:keys [subject-key]}]
    (s/validate NonBlankStr subject-key)
    [{:db/id (tempid :subjects -1)
      :agg/version 0
      :subject/id (subject-id study subject-key)
      :subject/key subject-key}
     [:db/add (:db/id study) :study/subjects (tempid :subjects -1)]
     [:event.fn/create :subject/created]]))

(defcommand odm-import/upsert-subject
  {:agg [:study/id :study-id]}
  (s/fn [db study :- Study _ {:keys [subject-key]}]
    (s/validate NonBlankStr subject-key)
    (if-let [subject (d/entity db [:subject/id (subject-id study subject-key)])]
      [[:agg.fn/inc-version (:db/id subject) (:agg/version subject)]
       [:event.fn/create :subject/updated]]
      [{:db/id (tempid :subjects -1)
        :agg/version 0
        :subject/id (subject-id study subject-key)
        :subject/key subject-key}
       [:db/add (:db/id study) :study/subjects (tempid :subjects -1)]
       [:event.fn/create :subject/created]])))

(defcommand odm-import/remove-subject
  {:agg [:study/id :study-id]}
  (s/fn [_ study :- Study _ {:keys [subject-key]}]
    (let [subject-ref [:subject/id (subject-id study subject-key)]]
      [[:db.fn/retractEntity subject-ref]
       [:db/retract (:db/id study) :study/subjects subject-ref]
       [:event.fn/create :subject/removed]])))
