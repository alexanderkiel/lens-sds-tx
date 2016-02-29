(ns lens.handlers.item-group
  (:require [clj-uuid :as uuid]
            [datomic.api :refer [tempid]]
            [lens.handlers.core :refer [defcommand resolve-entity Entity]]
            [lens.handlers.form :refer [Form]]
            [schema.core :as s :refer [Str]]))

(def ItemGroup
  (s/constrained Entity :item-group/id 'item-group))

(defcommand create-item-group
  {:aliases [:odm-import/insert-item-group]
   :agg-id-attr :form/id}
  (s/fn [form :- Form _ {:keys [item-group-oid]}]
    (s/validate Str item-group-oid)
    (when (some #(= item-group-oid (:item-group/oid %)) (:form/item-groups form))
      (throw (Exception. (str "The form " (:form/oid form) " contains already "
                              "an item-group with oid " item-group-oid "."))))
    [{:db/id (tempid :item-groups -1)
      :agg/version 0
      :item-group/id (uuid/v5 (:form/id form) item-group-oid)
      :item-group/oid item-group-oid}
     [:db/add (:db/id form) :form/item-groups (tempid :item-groups -1)]
     [:event.fn/create :item-group/created]]))
