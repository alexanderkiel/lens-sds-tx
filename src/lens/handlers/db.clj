(ns lens.handlers.db
  "Command handlers without aggregates."
  (:require [datomic.api :refer [tempid]]
            [lens.handlers.core :refer [defcommand Entity]]
            [lens.util :refer [NonBlankStr]]
            [schema.core :as s :refer [Str]]
            [clj-uuid :as uuid]))

(defn- study-id [study-oid]
  (uuid/v5 uuid/+null+ study-oid))

(defcommand create-study
  (fn [_ _ _ {:keys [study-oid]}]
    (s/validate NonBlankStr study-oid)
    [[:study.fn/create (tempid :studies -1) (study-id study-oid) study-oid]
     [:event.fn/create :study/created]]))
