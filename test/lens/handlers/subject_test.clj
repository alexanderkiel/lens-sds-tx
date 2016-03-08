(ns lens.handlers.subject-test
  (:require [clj-uuid :as uuid]
            [clojure.test :refer :all]
            [datomic.api :as d :refer [tempid]]
            [juxt.iota :refer [given]]
            [lens.handlers.subject]
            [lens.handlers.test-util :refer [database-fixture connect
                                             perform-command]]
            [schema.test :refer [validate-schemas]]))

(use-fixtures :once validate-schemas)
(use-fixtures :each database-fixture)

(deftest create-subject-perform-command
  (testing "success"
    (let [study {:db/id 1 :study/id (uuid/squuid) :study/oid "S001"}]
      (is (= (perform-command
               nil
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
               (d/db (connect))
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
                                   :subject/key "LI01"}]}
          {db :db-after tempids :tempids}
          @(d/transact
             (connect)
             [{:db/id (tempid :subjects -1)
               :agg/version 0
               :subject/id (uuid/v5 (:study/id study) "LI01")
               :subject/key "LI01"}
              [:db/add (:db/id study) :study/subjects (tempid :subjects -1)]
              [:event.fn/create :subject/created]])
          subject-eid (d/resolve-tempid db tempids (tempid :subjects -1))]
      (is (= (perform-command
               db
               study
               {:name :odm-import/upsert-subject
                :params {:subject-key "LI01"}})
             [[:agg.fn/inc-version subject-eid 0]
              [:event.fn/create :subject/updated]])))))
