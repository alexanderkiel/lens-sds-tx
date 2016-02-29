(ns lens.handlers.study
  (:require [datomic.api :refer [tempid]]
            [lens.handlers.core :refer [defcommand Entity]]
            [schema.core :as s :refer [Str]]
            [clj-uuid :as uuid]))

(def Study
  (s/constrained Entity :study/oid 'study?))

(defcommand create-study
  {}
  (fn [_ _ {:keys [study-oid]}]
    (s/validate Str study-oid)
    [[:study.fn/create (tempid :studies -1) (uuid/v5 uuid/+null+ study-oid)
      study-oid]
     [:event.fn/create :study/created]]))
