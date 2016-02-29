(ns user
  (:use plumbing.core)
  (:use criterium.core)
  (:require [clojure.pprint :refer [pprint pp]]
            [clojure.repl :refer :all]
            [clojure.tools.namespace.repl :refer [refresh]]
            [datomic.api :as d]
            [schema.core :as s]
            [com.stuartsierra.component :as comp]
            [lens.system :refer [new-system]]
            [environ.core :refer [env]]
            [lens.schema :refer [load-schema]]))

(s/set-fn-validation! true)

(def system nil)

(defn init []
  (when-not system (alter-var-root #'system (constantly (new-system env)))))

(defn start []
  (alter-var-root #'system comp/start))

(defn stop []
  (alter-var-root #'system comp/stop))

(defn startup []
  (init)
  (start))

(defn reset []
  (stop)
  (refresh :after 'user/startup))

(defn connect []
  (:conn (:db-creator system)))

;; Init Development
(comment
  (startup)
  )

;; Reset after making changes
(comment
  (reset)
  )

;; Connection and Database in the REPL
(comment
  (def conn (connect))
  (def db (d/db conn))
  )

;; Init Remote Console
(comment
  (in-ns 'user)
  (init)
  )

;; Schema Update
(comment
  (load-schema (connect))
  )

(comment
  (deref (d/transact-async (connect) [[:db.fn/retractEntity [:study/oid "S001"]]]))
  (d/q '[:find (count ?e) . :where [?e :study/id]] (d/db (connect)))
  (d/q '[:find ?id ?n :where [?s :study/oid ?id ?t] [?e :event/tx ?t] [?e :event/name ?n]] (d/db (connect)))
  (d/q '[:find (count ?e) . :where [?e :subject/id]] (d/db (connect)))
  (d/q '[:find (count ?s) . :where [?s :subject/id] [?s :agg/version ?v] [(< 0 ?v)]] (d/db (connect)))
  (d/q '[:find (count ?e) . :where [?e :study-event/id]] (d/db (connect)))
  (d/q '[:find (count ?e) . :where [?e :item/id]] (d/db (connect)))
  (d/q '[:find [?n ...] :where [?e :item/id _ ?t] [?t :cmd/sub ?n]] (d/db (connect)))
  (d/q '[:find [?n ...] :where [?e :item/id _ ?t] [?t :cmd/name ?n]] (d/db (connect)))
  )
