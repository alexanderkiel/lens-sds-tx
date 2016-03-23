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

(defn- assoc-command [m name command aliases]
  (reduce (fn [r name] (assoc r name command)) m (conj aliases name)))

(defn set-command! [name command & aliases]
  (swap! handler assoc-command name command aliases))

(defmacro defcommand
  {:arglists '([name doc-string? attr-map? fn])}
  [name & args]
  (let [m (if (string? (first args))
            {:doc (first args)}
            {})
        args (if (string? (first args))
                (next args)
                args)
        m (if (map? (first args))
            (conj m (first args))
            m)
        args (if (map? (first args))
                (next args)
                args)
        fn (first args)
        name (keyword name)
        aliases (mapv keyword (:aliases m))
        command (-> (select-keys m [:agg-id-attr])
                    (assoc :perform-command fn))]
    `(~'lens.handlers.core/set-command! ~name ~command ~@aliases)))
