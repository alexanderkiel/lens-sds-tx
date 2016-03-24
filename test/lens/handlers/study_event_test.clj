(ns lens.handlers.study-event-test
  (:require [clj-uuid :as uuid]
            [clojure.test :refer :all]
            [datomic.api :as d :refer [tempid]]
            [juxt.iota :refer [given]]
            [lens.handlers.test-util :refer [database-fixture connect
                                             perform-command]]
            [schema.test :refer [validate-schemas]]))

(use-fixtures :once validate-schemas)
