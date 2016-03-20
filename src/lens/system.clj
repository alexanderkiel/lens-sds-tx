(ns lens.system
  (:use plumbing.core)
  (:require [com.stuartsierra.component :as comp]
            [lens.server :refer [new-server]]
            [lens.broker :refer [new-broker]]
            [lens.datomic :refer [new-database-creator]]
            [lens.command-handler :refer [new-command-handler]]
            [lens.util :as u]))

(defnk new-system [lens-sds-tx-version db-uri {transact-parallelism "32"}
                   port broker-host
                   {broker-username "guest"} {broker-password "guest"}]
  (comp/system-map
    :version lens-sds-tx-version
    :port (u/parse-long port)
    :thread 4

    :db-creator
    (new-database-creator db-uri)

    :broker
    (new-broker {:host broker-host :username broker-username
                 :password broker-password})

    :command-handler
    (comp/using (new-command-handler (u/parse-long transact-parallelism))
                [:db-creator :broker])

    :server
    (comp/using (new-server) [:port :thread :db-creator :broker])))
