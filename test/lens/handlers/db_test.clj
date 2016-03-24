(ns lens.handlers.db-test
  (:require [lens.handlers.test-util :refer [perform-command]]
            [clojure.test :refer :all]
            [datomic.api :refer [tempid]]
            [schema.test :refer [validate-schemas]]
            [clj-uuid :as uuid]))

(use-fixtures :once validate-schemas)

(defn- study-id [study-oid]
  (uuid/v5 uuid/+null+ study-oid))

(deftest create-study
  (is (= (perform-command nil nil {:name :create-study :params {:study-oid "S001"}})
         [[:study.fn/create (tempid :studies -1) (study-id "S001") "S001"]
          [:event.fn/create :study/created]])))
