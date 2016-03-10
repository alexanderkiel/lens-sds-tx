(ns lens.handlers.test-util
  (:require [datomic.api :as d]
            [lens.handlers.core :refer [get-command]]
            [lens.schema :refer [load-schema]]))

(def db-uri "datomic:mem://test")

(defn connect [] (d/connect db-uri))

(defn database-fixture [f]
  (do
    (d/create-database db-uri)
    (load-schema (connect)))
  (f)
  (d/delete-database db-uri))

(defn perform-command [db agg {:keys [name params] :as command}]
  (if-let [cmd (get-command name)]
    (cmd db agg command params)
    (throw (Exception. (str "Command " name " not found.")))))
