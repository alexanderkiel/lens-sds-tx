(ns lens.handlers.subject-test
  (:require [lens.handlers.subject]
            [lens.handlers.test-util :refer [perform-command]]
            [clojure.test :refer :all]
            [datomic.api :refer [tempid]]
            [schema.test :refer [validate-schemas]]
            [juxt.iota :refer [given]]
            [clj-uuid :as uuid]))

(use-fixtures :once validate-schemas)

(deftest create-subject-perform-command
  (testing "success"
    (let [study {:db/id 1 :study/id (uuid/squuid) :study/oid "S001"}]
      (is (= (perform-command
               study
               {:name :create-subject
                :params {:subject-key "LI01"}})
             [{:db/id (tempid :subjects -1)
               :agg/version 0
               :subject/id (uuid/v5 (:study/id study) "LI01")
               :subject/key "LI01"}
              [:db/add (:db/id study) :study/subjects (tempid :subjects -1)]
              [:event.fn/create :subject/created]])))))

(deftest odm-import-upsert-subject-perform-command
  (testing "create on missing subject"
    (let [study {:db/id 1 :study/id (uuid/squuid) :study/oid "S001"}]
      (is (= (perform-command
               study
               {:name :odm-import/upsert-subject
                :params {:subject-key "LI01"}})
             [{:db/id (tempid :subjects -1)
               :agg/version 0
               :subject/id (uuid/v5 (:study/id study) "LI01")
               :subject/key "LI01"}
              [:db/add (:db/id study) :study/subjects (tempid :subjects -1)]
              [:event.fn/create :subject/created]]))))
  (testing "update on found subject"
    (let [study {:db/id 1 :study/id (uuid/squuid) :study/oid "S001"
                 :study/subjects [{:db/id 2 :agg/version 0
                                   :subject/key "LI01"}]}]
      (is (= (perform-command
               study
               {:name :odm-import/upsert-subject
                :params {:subject-key "LI01"}})
             [[:agg.fn/inc-version 2 0]
              [:event.fn/create :subject/updated]])))))
