(ns lens.handlers.item
  "Command handlers for the item aggregate."
  (:require [datomic.api :refer [tempid]]
            [lens.handlers.core :refer [defcommand resolve-entity Entity]]
            [schema.core :as s :refer [Str]]))

(def Item
  (s/constrained Entity :item/id 'item?))

(def DataType
  ":datetime is deprecated"
  (s/enum :string :integer :float :date-time :datetime))

(defn- coerce
  ":date-time is the current value but internally in the database
  we have still :datetime"
  [data-type]
  (if (= :date-time data-type)
    :datetime
    data-type))

(defn attr [data-type]
  (keyword "item" (str (name (coerce data-type)) "-value")))

(defcommand odm-import/update-item
  {:agg [:item/id :item-id]}
  (s/fn [_ item :- Item _ {:keys [data-type value]}]
    (s/validate DataType data-type)
    (if (some? ((attr data-type) item))
      [[:agg.fn/inc-version (:db/id item) (:agg/version item)]
       [:db/add (:db/id item) (attr data-type) value]
       [:event.fn/create :item/updated]]
      (throw (Exception. (str "Can't update the item " (:item/id item) " "
                              "with value " value " of data-type " data-type " "
                              "because the data type doesn't match the items "
                              "data type."))))))
