(ns lens.handlers.item
  "Command handlers for the item aggregate."
  (:require [datomic.api :refer [tempid]]
            [lens.handlers.core :refer [defcommand resolve-entity Entity]]
            [schema.core :as s :refer [Str]]))

(def Item
  (s/constrained Entity :item/id 'item?))

(def DataType
  (s/enum :string :integer :float :datetime))

(defn attr [data-type]
  (keyword "item" (str (name data-type) "-value")))

(defcommand odm-import/update-item
  {:agg [:item/id :item-id]}
  (s/fn [_ item :- Item _ {:keys [data-type value]}]
    (s/validate DataType data-type)
    [[:agg.fn/inc-version (:db/id item) (:agg/version item)]
     [:db/add (:db/id item) (attr data-type) value]
     [:event.fn/create :item/updated]]))
