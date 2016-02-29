(ns lens.handlers.core
  "Defines the macro defcommand which defines a command and collects it in an
  atom. The functions get-command and get-agg-id-attr can be used to retrieve
  commands and there aggregate identifier attributes."
  (:use plumbing.core)
  (:require [datomic.api :as d]
            [schema.core :as s]))

(def Entity
  (s/pred :db/id 'entity?))

(defn resolve-entity [{:keys [db-after tempids]} tempid]
  (some->> (d/resolve-tempid db-after tempids tempid) (d/entity db-after)))

(def handler (atom {}))

(defn get-command
  "Returns the commands function which take an aggregate or database, the
  command itself and the commands parameters."
  [name]
  (get-in @handler [name :perform-command]))

(defn get-agg-id-attr
  "Returns the commands aggregate identifer attribute which is used to build
  the lookup ref of the commands aggregate."
  [name]
  (get-in @handler [name :agg-id-attr]))

(defmacro defcommand [name {:keys [aliases agg-id-attr] :or {aliases []}} fn]
  (let [names (conj aliases (keyword name))
        command (assoc-when {:perform-command fn} :agg-id-attr agg-id-attr)]
    `(swap! ~'lens.handlers.core/handler #(reduce (fn [r# k#] (assoc r# k# ~command)) % ~names))))
