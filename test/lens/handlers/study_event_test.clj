(ns lens.handlers.study-event-test
  (:require [lens.handlers.test-util :refer [perform-command]]
            [clojure.test :refer :all]
            [datomic.api :refer [tempid]]
            [schema.test :refer [validate-schemas]]
            [juxt.iota :refer [given]]
            [clj-uuid :as uuid]))

(use-fixtures :once validate-schemas)

(deftest create-study-event-perform-command
  (testing "success"
    (let [subject {:db/id 1 :subject/id (uuid/squuid) :subject/key "LI01"}]
      (is (= (perform-command
               subject
               {:name :create-study-event
                :aid (:subject/id subject)
                :params {:study-event-oid "T01"}})
             [{:db/id (tempid :study-events -1)
               :agg/version 0
               :study-event/id (uuid/v5 (:subject/id subject) "T01")
               :study-event/oid "T01"}
              [:db/add (:db/id subject) :subject/study-events (tempid :study-events -1)]
              [:event.fn/create :study-event/created]])))))

(deftest odm-import-upsert-study-event-perform-command
  (testing "create on missing study-event"
    (let [subject {:db/id 1 :subject/id (uuid/squuid) :subject/key "LI01"}]
      (is (= (perform-command
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
    (let [subject {:db/id 1 :subject/id (uuid/squuid) :subject/key "LI01"
                   :subject/study-events [{:db/id 2 :agg/version 0
                                           :study-event/oid "T01"}]}]
      (is (= (perform-command
               subject
               {:name :odm-import/upsert-study-event
                :params {:study-event-oid "T01"}})
             [[:agg.fn/inc-version 2 0]
              [:event.fn/create :study-event/updated]])))))
