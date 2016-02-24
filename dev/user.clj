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
            [lens.api :as api]
            [environ.core :refer [env]]
            [lens.schema :refer [load-schema]]
            [lens.broker :as b]))

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
  (d/connect (:db-uri system)))

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
  (load-schema conn)
  )

(defn items [db study-id]
  (d/q '[:find [?i ...]
         :in $ ?s-id
         :where
         [?s :study/oid ?s-id]
         [?s :study/subjects ?sub]
         [?sub :subject/study-events ?se]
         [?se :study-event/forms ?f]
         [?f :form/item-groups ?ig]
         [?ig :item-group/items ?i]] db study-id))

(defn create-subjects [conn study-oid start end]
  (let [study (api/study (d/db conn) [:db/id :study/oid] study-oid)]
    (doseq [key (range start end)]
      (api/create-subject conn study (format "LI%08d" key)))))

(defn create-study-events [conn study-oid & study-event-oids]
  (let [study (api/study (d/db conn) [:db/id :study/oid] study-oid)]
    (->> (for [subject (api/subjects-by-study (d/db conn) [:db/id] study)
               study-event-oid study-event-oids]
           [:study-event.fn/create (d/tempid :study-events)
            (:db/id subject) study-event-oid])
         (d/transact-async conn)
         (deref))))

(defn create-forms [conn study-oid start end]
  (let [study (api/study (d/db conn) [:db/id :study/oid] study-oid)
        study-events (api/study-events-by-study (d/db conn) [:db/id] study)]
    (doseq [form-oid (map #(format "T%05d" %) (range start end))]
      (println form-oid)
      (->> (for [study-event study-events]
             [:form.fn/create (d/tempid :forms) (:db/id study-event)
              form-oid])
           (d/transact-async conn)
           (deref)))))

(defn create-item-groups [conn study-oid start end]
  (let [db (d/db conn)
        study (api/study db [:db/id :study/oid] study-oid)]
    (doseq [subject (sort-by :subject/key (api/subjects-by-study db [:db/id :subject/key] study))]
      (println (:subject/key subject))
      (->> (for [form (api/forms-by-subject db [:db/id :form/oid] subject)
                 item-group-oid (map #(format "%s_IG%05d" (:form/oid form) %) (range start end))]
             [:item-group.fn/create (d/tempid :item-groups) (:db/id form) item-group-oid])
           (d/transact-async conn)
           (deref)))))

(defn create-items [conn study-oid start end]
  (let [db (d/db conn)
        study (api/study db [:db/id :study/oid] study-oid)]
    (doseq [subject (sort-by :subject/key (api/subjects-by-study db [:db/id :subject/key] study))]
      (println (:subject/key subject))
      (->> (for [item-group (api/item-groups-by-subject db [:db/id :item-group/oid] subject)
                 item-oid (map #(format "%s_I%05d" (:item-group/oid item-group) %) (range start end))]
             [:item.fn/create (d/tempid :items) (:db/id item-group) item-oid :integer (long (* (Math/random) 100))])
           (d/transact-async conn)
           (deref)))))

(comment
  (startup)
  (reset)
  (pst)
  (def conn (connect))
  (load-schema conn)
  (def db (d/db conn))

  (deref (d/transact-async conn [[:db.fn/retractEntity [:study/oid "S001"]]]))

  ;; 2 Studies
  (api/create-study conn "S001")
  (api/create-study conn "S002")
  (count (api/studies (d/db conn) [:db/id]))

  ;; 13.000 Subjects
  (create-subjects conn "S001" 0 3000)
  (create-subjects conn "S002" 0 10000)
  (let [db (d/db conn) study (api/study db [:db/id :study/oid] "S001")]
    (time (count (api/subjects-by-study db [:db/id] study))))

  ;; 26.000 Study Events
  (create-study-events conn "S001" "A1_HAUPT01" "A1_PILOT")
  (create-study-events conn "S002" "A2_01" "A2_02")
  (let [db (d/db conn) study (api/study db [:db/id :study/oid] "S001")]
    (time (count (api/study-events-by-study db [:db/id] study))))

  ;; 26.000 Forms
  (create-forms conn "S001" 0 1)
  (create-forms conn "S002" 0 1)
  (let [db (d/db conn) study (api/study db [:db/id :study/oid] "S001")]
    (time (count (api/forms-by-study db [:db/id] study))))

  ;; 26.000 Item Groups
  (create-item-groups conn "S001" 0 1)
  (create-item-groups conn "S002" 0 1)
  (let [db (d/db conn) study (api/study db [:db/id :study/oid] "S001")]
    (time (count (api/item-groups-by-study db [:db/id] study))))

  ;; 26.000 Items
  (create-items conn "S001" 0 1)
  (create-items conn "S002" 1 20)
  (let [db (d/db conn) study (api/study db [:db/id :study/oid] "S001")]
    (time (count (api/items-by-study db [:db/id] study))))

  (let [db (d/db conn) study (api/study db [:db/id :study/oid] "S001")]
    (take 1 (api/items-by-study db [:db/id :item/oid] study)))

  ;; 26.000 Items
  (count (api/items-by-oid (d/db conn) '[*] "T00000_IG00000_I00000"))

  ;; 26.000 Items, 20000 in S002 - 270ms (tested two times)
  (let [db (d/db conn) study (api/study db [:db/id :study/oid] "S001")]
    (quick-bench (count (api/study-events-by-item-query db study "T00000_IG00000_I00000" '(< 50 ?v)))))

  ;; count study-events of one form-def: 300 ms
  (time (d/q '[:find (count ?se) .
               :in $ ?s-id ?fd-id
               :where
               [?f :form/oid ?fd-id]
               [?se :study-event/forms ?f]
               [?sub :subject/study-events ?se]
               [?s :study/subjects ?sub]
               [?s :study/oid ?s-id]]
             (d/db conn) "S001" "T00001"))

  ;; count study-events of one item-group-def: 300 ms
  (time (d/q '[:find (count ?se) .
               :in $ ?s-id ?fd-id
               :where
               [?f :form/oid ?fd-id]
               [?se :study-event/forms ?f]
               [?sub :subject/study-events ?se]
               [?s :study/subjects ?sub]
               [?s :study/oid ?s-id]]
             (d/db conn) "S001" "T00001"))

  (d/q '[:find (count ?ig) .
         :in $ ?igd-id
         :where
         [?ig :item-group/oid ?igd-id]]
       (d/db conn) "IG00000")

  )
