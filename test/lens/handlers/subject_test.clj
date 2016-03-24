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

(deftest create-study-event
  (testing "success"
    (let [subject {:db/id 1 :subject/id (uuid/squuid) :subject/key "LI01"}]
      (is (= (perform-command
               nil
               subject
               {:name :create-study-event
                :params {:study-event-oid "T01"}})
             [{:db/id (tempid :study-events -1)
               :agg/version 0
               :study-event/id (uuid/v5 (:subject/id subject) "T01")
               :study-event/oid "T01"}
              [:db/add (:db/id subject) :subject/study-events (tempid :study-events -1)]
              [:event.fn/create :study-event/created]])))))

(deftest odm-import-upsert-study-event
  (testing "create on missing study-event"
    (let [subject {:db/id 1 :subject/id (uuid/squuid) :subject/key "LI01"}]
      (is (= (perform-command
               (d/db (connect))
               subject
               {:name :odm-import/upsert-study-event
                :params {:study-event-oid "T01"}})
             [{:db/id (tempid :study-events -1)
               :agg/version 0
               :study-event/id (uuid/v5 (:subject/id subject) "T01")
               :study-event/oid "T01"}
              [:db/add (:db/id subject) :subject/study-events (tempid :study-events -1)]
              [:event.fn/create :study-event/created]]))))
  (testing "update on found study-event"
    (let [subject {:db/id 1 :subject/id (uuid/squuid) :subject/key "LI01"}
          {db :db-after tempids :tempids}
          @(d/transact
             (connect)
             [{:db/id (tempid :study-events -1)
               :agg/version 0
               :study-event/id (uuid/v5 (:subject/id subject) "T01")
               :study-event/oid "T01"}
              [:db/add (:db/id subject) :subject/study-events (tempid :study-events -1)]
              [:event.fn/create :study-event/created]])
          study-event-eid (d/resolve-tempid db tempids (tempid :study-events -1))]
      (is (= (perform-command
               db
               subject
               {:name :odm-import/upsert-study-event
                :params {:study-event-oid "T01"}})
             [[:agg.fn/inc-version study-event-eid 0]
              [:event.fn/create :study-event/updated]])))))
