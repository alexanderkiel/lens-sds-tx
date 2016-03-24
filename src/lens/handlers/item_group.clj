(ns lens.handlers.item-group
  "Command handlers for the item-group aggregate."
  (:require [clj-uuid :as uuid]
            [datomic.api :refer [tempid]]
            [lens.handlers.core :refer [defcommand resolve-entity Entity]]
            [lens.handlers.item :as i :refer [DataType]]
            [lens.util :refer [NonBlankStr]]
            [schema.core :as s :refer [Str]]))

(def ItemGroup
  (s/constrained Entity :item-group/id 'item-group?))

(defn item-id [item-group item-oid]
  (uuid/v5 (:item-group/id item-group) item-oid))

(defcommand create-item
  {:aliases [:odm-import/insert-item]
   :agg [:item-group/id :item-group-id]}
  (s/fn [_ item-group :- ItemGroup _ {:keys [item-oid data-type value]}]
    (s/validate NonBlankStr item-oid)
    (s/validate DataType data-type)
    [{:db/id (tempid :items -1)
      :agg/version 0
      :item/id (item-id item-group item-oid)
      :item/oid item-oid
      (i/attr data-type) value}
     [:db/add (:db/id item-group) :item-group/items (tempid :items -1)]
     [:event.fn/create :item/created]]))

(defcommand odm-import/remove-item
  {:agg [:item-group/id :item-group-id]}
  (s/fn [_ item-group :- ItemGroup _ {:keys [item-oid]}]
    (let [item-ref [:item/id (item-id item-group item-oid)]]
      [[:db.fn/retractEntity item-ref]
       [:db/retract (:db/id item-group) :item-group/items item-ref]
       [:event.fn/create :item/removed]])))
