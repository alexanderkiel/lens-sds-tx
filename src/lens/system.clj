(ns lens.system
  (:use plumbing.core)
  (:require [com.stuartsierra.component :as comp]
            [lens.server :refer [new-server]]
            [lens.broker :refer [new-broker]]
            [lens.database :refer [new-database]]
            [lens.command-handler :refer [new-command-handler]]
            [lens.util :as u]))

(defnk new-system [lens-sds-tx-version db-uri {transact-parallelism "32"}
                   port broker-host
                   {broker-username "guest"} {broker-password "guest"}]
  (comp/system-map
    :version lens-sds-tx-version
    :port (u/parse-long port)
    :thread 4

    :database
    (new-database db-uri)

    :broker
    (new-broker {:host broker-host :username broker-username
                 :password broker-password})

    :command-handler
    (comp/using (new-command-handler (u/parse-long transact-parallelism))
                [:database :broker])

    :server
    (comp/using (new-server) [:port :thread :database :broker])))
