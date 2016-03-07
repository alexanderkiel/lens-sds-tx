(ns lens.handlers.form
  (:require [clj-uuid :as uuid]
            [datomic.api :refer [tempid]]
            [lens.handlers.core :refer [defcommand resolve-entity Entity]]
            [lens.handlers.study-event :refer [StudyEvent]]
            [schema.core :as s :refer [Str]]
            [datomic.api :as d]))

(def Form
  (s/constrained Entity :form/id 'form?))

(defcommand create-form
  {:aliases [:odm-import/insert-form]
   :agg-id-attr :study-event/id}
  (s/fn [db study-event :- StudyEvent _ {:keys [form-oid]}]
    (s/validate Str form-oid)
    (let [form-id (uuid/v5 (:study-event/id study-event) form-oid)]
      (when (d/entity db [:form/id form-id])
        (throw (Exception. (str "The study-event " (:study-event/oid study-event) " "
                                "contains already a form with oid " form-oid "."))))
      [{:db/id (tempid :forms -1)
        :agg/version 0
        :form/id form-id
        :form/oid form-oid}
       [:db/add (:db/id study-event) :study-event/forms (tempid :forms -1)]
       [:event.fn/create :form/created]])))
