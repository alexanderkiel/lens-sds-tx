(ns lens.handlers.subject
  (:require [clj-uuid :as uuid]
            [datomic.api :refer [tempid]]
            [lens.handlers.core :refer [defcommand resolve-entity Entity]]
            [lens.handlers.study :refer [Study]]
            [schema.core :as s :refer [Str]]))

(def Subject
  (s/constrained Entity :subject/id 'subject?))

(defn- find-subject [study subject-key]
  (some #(when (= subject-key (:subject/key %)) %) (:study/subjects study)))

(defn- duplicate [study subject-key]
  (Exception. (str "The study " (:study/oid study) " contains already "
                   "a subject with key " subject-key ".")))

(defcommand create-subject
  {:aliases [:odm-import/insert-subject]
   :agg-id-attr :study/id}
  (s/fn [study :- Study _ {:keys [subject-key]}]
    (s/validate Str subject-key)
    (when (find-subject study subject-key)
      (throw (duplicate study subject-key)))
    [{:db/id (tempid :subjects -1)
      :agg/version 0
      :subject/id (uuid/v5 (:study/id study) subject-key)
      :subject/key subject-key}
     [:db/add (:db/id study) :study/subjects (tempid :subjects -1)]
     [:event.fn/create :subject/created]]))

(defcommand odm-import/upsert-subject
  {:agg-id-attr :study/id}
  (s/fn [study :- Study _ {:keys [subject-key]}]
    (s/validate Str subject-key)
    (if-let [subject (find-subject study subject-key)]
      [[:agg.fn/inc-version (:db/id subject) (:agg/version subject)]
       [:event.fn/create :subject/updated]]
      [{:db/id (tempid :subjects -1)
        :agg/version 0
        :subject/id (uuid/v5 (:study/id study) subject-key)
        :subject/key subject-key}
       [:db/add (:db/id study) :study/subjects (tempid :subjects -1)]
       [:event.fn/create :subject/created]])))
