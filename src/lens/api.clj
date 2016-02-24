(ns lens.api
  (:use plumbing.core)
  (:require [schema.core :as s]
            [lens.util :as u :refer [NonBlankStr NonNegInt]]
            [datomic.api :as d]))

;; ---- Schemas ---------------------------------------------------------------

(def EId
  (s/named NonNegInt "eid"))

(def OID
  (s/named NonBlankStr "OID"))

(def Entity
  (s/pred :db/id 'entity?))

(def Study
  (s/constrained Entity :study/oid 'study?))

(def Subject
  (s/constrained Entity :subject/key 'subject?))

(def StudyEvent
  (s/constrained Entity :study-event/oid 'study-event?))

(def Form
  (s/constrained Entity :form/oid 'form?))

(def ItemGroup
  (s/constrained Entity :item-group/oid 'item-group?))

(def Item
  (s/constrained Entity :item/oid 'item?))

(def DataType
  (s/enum :string :integer :datetime))

;; ---- Util ------------------------------------------------------------------

(defn- create [conn partition create-fn & args]
  (try
    (u/create conn partition (fn [tid] [(apply vector create-fn tid args)]))
    (catch Exception e
      (when-not (= :duplicate (u/error-type e)) (throw e)))))

(def study-rules
  '[[(subject ?s ?sub)
     [?s :study/subjects ?sub]]
    [(study-event ?s ?se)
     (subject ?s ?sub)
     [?sub :subject/study-events ?se]]
    [(form ?s ?f)
     (study-event ?s ?se)
     [?se :study-event/forms ?f]]
    [(item-group ?s ?ig)
     (form ?s ?f)
     [?f :form/item-groups ?ig]]
    [(item ?s ?i)
     (item-group ?s ?ig)
     [?ig :item-group/items ?i]]])

(def subject-rules
  '[[(study-event ?sub ?se)
     [?sub :subject/study-events ?se]]
    [(form ?sub ?f)
     (study-event ?sub ?se)
     [?se :study-event/forms ?f]]
    [(item-group ?sub ?ig)
     (form ?sub ?f)
     [?f :form/item-groups ?ig]]
    [(item ?sub ?i)
     (item-group ?sub ?ig)
     [?ig :item-group/items ?i]]])

;; ---- Study -----------------------------------------------------------------

(s/defn create-study
  "Creates a study with oid.

  More is currently not used.

  Returns the created study or nil if there is already one with oid."
  [conn oid :- OID]
  (create conn :studies :study.fn/create oid))

(s/defn study [db pull-pattern oid :- OID]
  (d/pull db pull-pattern [:study/oid oid]))

(s/defn studies [db pull-pattern]
  (->> (d/q '[:find [?s ...] :where [?s :study/oid]] db)
       (d/pull-many db pull-pattern)))

;; ---- Subject ---------------------------------------------------------------

(s/defn create-subject
  "Creates a subject with key within study with oid.

  Returns the created subject or nil if there is already one with key."
  [conn study-oid :- OID key :- NonBlankStr]
  (create conn :studies :subject.fn/create (:db/id study-oid) key))

(s/defn subjects-by-study [db pull-pattern study :- Study]
  (->> (d/q '[:find [?sub ...]
              :in $ % ?s
              :where (subject ?s ?sub)] db study-rules (:db/id study))
       (d/pull-many db pull-pattern)))

;; ---- Study Event -----------------------------------------------------------

(s/defn create-study-event
  "Creates a study event of a subject referencing a study event def.

  Returns the created study event or nil if there is already one."
  [conn subject :- Subject study-event-oid :- OID]
  (create conn :study-events :study-event.fn/create (:db/id subject)
          study-event-oid))

(s/defn study-events-by-study [db pull-pattern study :- Study]
  (->> (d/q '[:find [?se ...]
              :in $ % ?s
              :where (study-event ?s ?se)] db study-rules (:db/id study))
       (d/pull-many db pull-pattern)))

;; ---- Form ------------------------------------------------------------------

(s/defn create-form
  "Creates a form of a study event referencing a form def.

  Returns the created form or nil if there is already one."
  ([conn study-event :- StudyEvent form-oid :- OID]
    (create conn :forms :form.fn/create (:db/id study-event) form-oid))
  ([conn study-event :- StudyEvent form-oid :- OID repeat-key]
    (create conn :forms :form.fn/create (:db/id study-event) form-oid
            repeat-key)))

(s/defn forms-by-study [db pull-pattern study :- Study]
  (->> (d/q '[:find [?f ...]
              :in $ % ?s
              :where (form ?s ?f)] db study-rules (:db/id study))
       (d/pull-many db pull-pattern)))

(s/defn forms-by-subject [db pull-pattern subject :- Subject]
  (->> (d/q '[:find [?f ...]
              :in $ % ?sub
              :where (form ?sub ?f)] db subject-rules (:db/id subject))
       (d/pull-many db pull-pattern)))

;; ---- Item Group ------------------------------------------------------------

(s/defn create-item-group
  "Creates an item group of a form referencing an item group def.

  Returns the created item group or nil if there is already one."
  ([conn form :- Form item-group-oid :- OID]
    (create conn :item-groups :item-group.fn/create (:db/id form)
            item-group-oid))
  ([conn form :- StudyEvent item-group-oid :- OID repeat-key]
    (create conn :item-groups :item-group.fn/create (:db/id form)
            item-group-oid repeat-key)))

(s/defn item-groups-by-study [db pull-pattern study :- Study]
  (->> (d/q '[:find [?ig ...]
              :in $ % ?s
              :where (item-group ?s ?ig)] db study-rules (:db/id study))
       (d/pull-many db pull-pattern)))

(s/defn item-groups-by-subject [db pull-pattern subject :- Subject]
  (->> (d/q '[:find [?ig ...]
              :in $ % ?sub
              :where (item-group ?sub ?ig)] db subject-rules (:db/id subject))
       (d/pull-many db pull-pattern)))

;; ---- Item ------------------------------------------------------------------

(s/defn create-item
  "Creates an item of an item group referencing an item def.

  Returns the created item or nil if there is already one."
  [conn item-group :- ItemGroup item-oid :- OID data-type value]
  (create conn :items :item.fn/create (:db/id item-group) item-oid data-type
          value))

(s/defn items-by-study [db pull-pattern study :- Study]
  (->> (d/q '[:find [?i ...]
              :in $ % ?s
              :where (item ?s ?i)] db study-rules (:db/id study))
       (d/pull-many db pull-pattern)))

(s/defn items-by-oid [db pull-pattern oid :- OID]
  (->> (d/q '[:find [?i ...]
              :in $ ?oid
              :where [?i :item/oid ?oid]] db oid)
       (d/pull-many db pull-pattern)))

(defn- predicate-rule
  "Takes a predicate like (< 10 ?v) and returns an (item-value ?i) rule. The
  predicate has to use ?v as value placeholder."
  [predicate]
  (conj '[(item-value ?i) [?i :item/integer-value ?v]] [predicate]))

(s/defn study-events-by-item-query [db study :- Study item-oid :- OID predicate]
  (d/q '[:find [?se ...]
         :in $ % ?s ?item-oid
         :where
         [?i :item/oid ?item-oid]
         (item-value ?i)
         [?ig :item-group/items ?i]
         [?f :form/item-groups ?ig]
         [?se :study-event/forms ?f]
         [?sub :subject/study-events ?se]
         [?s :study/subjects ?sub]]
       db [(predicate-rule predicate)] (:db/id study) item-oid))

;; ---- Form Subject Counts ---------------------------------------------------

(s/defn form-subject-counts-by-study [db study :- Study]
  (->> (d/q '[:find ?f-oid (count ?sub)
              :in $ ?s
              :where
              [?s :study/subjects ?sub]
              [?sub :subject/study-events ?se]
              [?se :study-event/forms ?f]
              [?f :form/oid ?f-oid]]
            db (:db/id study))
       (into {})))

(s/defn form-subject-counts [db]
  (for-map [study (studies db [:db/id :study/oid])]
    (:study/oid study)
    (form-subject-counts-by-study db study)))
