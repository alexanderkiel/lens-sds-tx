(ns lens.handlers.form
  "Command handlers for the form aggregate."
  (:require [clj-uuid :as uuid]
            [datomic.api :refer [tempid]]
            [lens.handlers.core :refer [defcommand resolve-entity Entity]]
            [lens.util :refer [NonBlankStr]]
            [schema.core :as s :refer [Str]]))

(def Form
  (s/constrained Entity :form/id 'form?))

(defn- item-group-id [form item-group-oid]
  (uuid/v5 (:form/id form) item-group-oid))

(defcommand create-item-group
  {:aliases [:odm-import/insert-item-group]
   :agg [:form/id :form-id]}
  (s/fn [_ form :- Form _ {:keys [item-group-oid]}]
    (s/validate NonBlankStr item-group-oid)
    [{:db/id (tempid :item-groups -1)
      :agg/version 0
      :item-group/id (item-group-id form item-group-oid)
      :item-group/oid item-group-oid}
     [:db/add (:db/id form) :form/item-groups (tempid :item-groups -1)]
     [:event.fn/create :item-group/created]]))

(defcommand odm-import/remove-item-group
  {:agg [:form/id :form-id]}
  (s/fn [_ form :- Form _ {:keys [item-group-oid]}]
    (let [item-group-ref [:item-group/id (item-group-id form item-group-oid)]]
      [[:db.fn/retractEntity item-group-ref]
       [:db/retract (:db/id form) :form/item-groups item-group-ref]
       [:event.fn/create :item-group/removed]])))
