(ns lens.system
  (:use plumbing.core)
  (:require [com.stuartsierra.component :as comp]
            [lens.server :refer [new-server]]
            [lens.broker :refer [new-broker]]
            [lens.datomic :refer [new-database-creator]]
            [lens.util :as u]
            [lens.command-handler]))

(defnk new-system [lens-sds-tx-version db-uri port broker-host]
  (comp/system-map
    :version lens-sds-tx-version
    :db-uri db-uri
    :port (u/parse-long port)
    :thread 4

    :db-creator
    (comp/using (new-database-creator) [:db-uri])

    :broker
    (comp/using (new-broker {:host broker-host :num-threads 16}) [:db-uri :db-creator])

    :server
    (comp/using (new-server) [:db-uri :port :thread :db-creator :broker])))
