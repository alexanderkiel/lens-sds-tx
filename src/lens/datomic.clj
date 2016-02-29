(ns lens.datomic
  (:require [com.stuartsierra.component :refer [Lifecycle]]
            [datomic.api :as d]
            [lens.schema :refer [load-schema]]
            [lens.logging :as log]))

(defn- info [msg]
  (log/info {:component "DatabaseCreator" :msg msg}))

(defrecord DatabaseCreator [db-uri conn]
  Lifecycle
  (start [creator]
    (info (str "Start database creator on " db-uri))
    (when (d/create-database db-uri)
      (load-schema (d/connect db-uri)))
    (assoc creator :conn (d/connect db-uri)))
  (stop [creator]
    (info "Stop database creator.")
    (assoc creator :conn nil)))

(defn new-database-creator
  "Ensures that the database at db-uri exists."
  [db-uri]
  (map->DatabaseCreator {:db-uri db-uri}))
