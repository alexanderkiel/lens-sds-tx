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
  "Returns the command with name."
  [name]
  (get @handler name))

(defn get-command-fn
  "Returns the commands function which take an aggregate or database, the
  command itself and the commands parameters."
  [name]
  (:perform-command (get-command name)))

(defn- assoc-command [m name command aliases]
  (reduce (fn [r name] (assoc r name command)) m (conj aliases name)))

(defn set-command! [name command & aliases]
  (swap! handler assoc-command name command aliases))

(defmacro defcommand
  "Defines a command handler."
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
        agg (some->> (:agg m) (zipmap [:id-attr :param-key]))
        command (assoc-when {:perform-command fn} :agg agg)]
    `(~'lens.handlers.core/set-command! ~name ~command ~@aliases)))

(get-command :create-study)
