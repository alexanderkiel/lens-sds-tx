(ns lens.handlers.item
  (:require [clj-uuid :as uuid]
            [datomic.api :refer [tempid]]
            [lens.handlers.core :refer [defcommand resolve-entity Entity]]
            [lens.handlers.item-group :refer [ItemGroup]]
            [schema.core :as s :refer [Str]]))

(def DataType
  (s/enum :string :integer :float :datetime))

(defcommand create-item
  {:aliases [:odm-import/insert-item]
   :agg-id-attr :item-group/id}
  (s/fn [_ item-group :- ItemGroup _ {:keys [item-oid data-type value]}]
    (s/validate Str item-oid)
    (s/validate DataType data-type)
    (when (some #(= item-oid (:item/oid %)) (:item-group/items item-group))
      (throw (Exception. (str "The item-group " (:item-group/oid item-group) " "
                              "contains already an item with oid " item-oid "."))))
    [{:db/id (tempid :items -1)
      :agg/version 0
      :item/id (uuid/v5 (:item-group/id item-group) item-oid)
      :item/oid item-oid
      (keyword "item" (str (name data-type) "-value")) value}
     [:db/add (:db/id item-group) :item-group/items (tempid :items -1)]
     [:event.fn/create :item/created]]))
