(ns lens.handlers.subject
  (:require [clj-uuid :as uuid]
            [datomic.api :as d :refer [tempid]]
            [lens.handlers.core :refer [defcommand resolve-entity Entity]]
            [lens.handlers.study :refer [Study]]
            [schema.core :as s :refer [Str]]))

(def Subject
  (s/constrained Entity :subject/id 'subject?))

(defcommand create-subject
  "Creates a subject in study using stubject-key."
  {:aliases [:odm-import/insert-subject]
   :agg-id-attr :study/id}
  (s/fn [_ study :- Study _ {:keys [subject-key]}]
    (s/validate Str subject-key)
    [{:db/id (tempid :subjects -1)
      :agg/version 0
      :subject/id (uuid/v5 (:study/id study) subject-key)
      :subject/key subject-key}
     [:db/add (:db/id study) :study/subjects (tempid :subjects -1)]
     [:event.fn/create :subject/created]]))

(defcommand odm-import/upsert-subject
  {:agg-id-attr :study/id}
  (s/fn [db study :- Study _ {:keys [subject-key]}]
    (s/validate Str subject-key)
    (let [subject-id (uuid/v5 (:study/id study) subject-key)]
      (if-let [subject (d/entity db [:subject/id subject-id])]
        [[:agg.fn/inc-version (:db/id subject) (:agg/version subject)]
         [:event.fn/create :subject/updated]]
        [{:db/id (tempid :subjects -1)
          :agg/version 0
          :subject/id subject-id
          :subject/key subject-key}
         [:db/add (:db/id study) :study/subjects (tempid :subjects -1)]
         [:event.fn/create :subject/created]]))))
