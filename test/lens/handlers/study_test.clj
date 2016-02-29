(ns lens.handlers.study-test
  (:require [lens.handlers.test-util :refer [perform-command]]
            [clojure.test :refer :all]
            [datomic.api :refer [tempid]]
            [schema.test :refer [validate-schemas]]
            [clj-uuid :as uuid]))

(use-fixtures :once validate-schemas)

(deftest create-study-test
  (is (= (perform-command nil {:name :create-study :params {:study-oid "S001"}})
         [[:study.fn/create (tempid :studies -1) (uuid/v5 uuid/+null+ "S001") "S001"]
          [:event.fn/create :study/created]])))
