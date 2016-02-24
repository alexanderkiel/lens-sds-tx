(ns lens.schema
  "Functions to load the schema.

  Usage:

    (load-base-schema conn)"
  (:use plumbing.core)
  (:require [datomic.api :as d])
  (:refer-clojure :exclude [alias]))

(defn- enum [enum]
  {:db/id (d/tempid :db.part/user)
   :db/ident enum})

(defmacro func [name doc params & code]
  `{:db/id (d/tempid :db.part/user)
    :db/ident (keyword '~name)
    :db/doc ~doc
    :db/fn (d/function '{:lang "clojure" :params ~params
                         :requires [[clojure.core.reducers]]
                         :code (do ~@code)})})

(defn- assoc-opt [opt]
  (case opt
    :id [:db/unique :db.unique/identity]
    :unique [:db/unique :db.unique/value]
    :index [:db/index true]
    :fulltext [:db/fulltext true]
    :many [:db/cardinality :db.cardinality/many]
    :comp [:db/isComponent true]))

(defn- assoc-opts [entity-map opts]
  (into entity-map (map assoc-opt) opts))

(defn- assoc-tempid [m partition]
  (assoc m :db/id (d/tempid partition)))

(defn- make-part
  "Assocs :db/id and :db.install/_partition to the part map."
  [part]
  (-> (assoc-tempid part :db.part/db)
      (assoc :db.install/_partition :db.part/db)))

(defn- make-attr
  "Assocs :db/id and :db.install/_attribute to the attr map."
  [attr]
  (-> (assoc-tempid attr :db.part/db)
      (assoc :db.install/_attribute :db.part/db)))

(defn- build-attr-map [entity-name def-item]
  (let [[attr type & more] def-item
        [opts doc] (if (string? (last more))
                     [(butlast more) (last more)]
                     [more nil])]
    (-> {:db/ident (keyword entity-name (name attr))
         :db/valueType (keyword "db.type" (name type))
         :db/cardinality :db.cardinality/one}
        (assoc-opts opts)
        (assoc-when :db/doc doc)
        (make-attr))))

(defn build-function [entity-name def-item]
  (update-in def-item [:db/ident] #(keyword (str entity-name ".fn") (name %))))

(defn- def-item-tx-builder [entity-name]
  (fn [def-item]
    (cond
      (sequential? def-item)
      (build-attr-map entity-name def-item)

      (:db/fn def-item)
      (build-function entity-name def-item)

      :else def-item)))

(defn- build-entity-tx [tx name def]
  (into tx (map (def-item-tx-builder (clojure.core/name name))) def))

(defn build-tx [entities]
  (reduce-kv build-entity-tx [] entities))

(def event
  "An business event like creating a subject."
  [[:id :uuid :unique]
   [:cid :uuid :index]
   [:name :keyword :index]
   [:sub :string :index "The subject which caused the event."]

   (func create
     "Creates an event."
     [db id cid name sub]
     [{:db/id (d/tempid :db.part/tx)
       :event/id id
       :event/cid cid
       :event/name name
       :event/sub sub}])])

(def study
  "A clinical or epidemiological study."
  [[:oid :string :id]
   [:subjects :ref :many :comp]

   (func create
     "Creates a study."
     [db tid oid]
     (if-not (d/entity db [:study/oid oid])
       [{:db/id tid
         :study/oid oid}]
       (throw (ex-info "Duplicate!" {:type :duplicate}))))])

(def subject
  "A subject is a patient participating in the study."
  [[:id :uuid :unique]
   [:key :string :index "The id of the subject unique within its study."]
   [:study-events :ref :many :comp]

   (func create
     "Creates a subject."
     [db tid study-oid key]
     (if-let [study (:db/id (d/entity db [:study/oid study-oid]))]
       (if-not (d/q '[:find ?sub .
                      :in $ ?s ?key
                      :where
                      [?sub :subject/key ?key]
                      [?s :study/subjects ?sub]]
                    db study key)
         [{:db/id tid
           :subject/id (d/squuid)
           :subject/key key}
          [:db/add study :study/subjects tid]]
         (throw (ex-info (format "Duplicate subject %s in study %s."
                                 key study-oid)
                         {:type :duplicate
                          :subject-key key
                          :study-oid study-oid})))
       (throw (ex-info "Study not found." {:type :study-not-found}))))])

(def study-event
  "A study event is a reusable package of forms usually corresponding to a study
  data-collection event."
  [[:id :uuid :unique]
   [:oid :string :index]
   [:repeat-key :string "A key used to distinguish between repeats of the same
                        type of study event for a single subject. (optional)"]
   [:forms :ref :many :comp]

   (func create
     ""
     [db tid subject-id oid]
     (if-let [subject (:db/id (d/entity db [:subject/id subject-id]))]
       (if-not (d/q '[:find ?se .
                      :in $ ?sub ?oid
                      :where
                      [?sub :subject/study-events ?se]
                      [?se :study-event/oid ?oid]]
                    db subject oid)
         [{:db/id tid
           :study-event/id (d/squuid)
           :study-event/oid oid}
          [:db/add subject :subject/study-events tid]]
         (throw (ex-info "Duplicate!" {:type :duplicate})))
       (throw (ex-info "Subject not found." {:type :subject-not-found}))))])

(def form
  "A form is analogous to a page in a paper CRF book or electronic CRF screen. A
  form generally collects a set of logically and temporally related information.
  A series of forms is collected as part of a study event."
  [[:id :uuid :unique]
   [:oid :string :index]
   [:repeat-key :string "A key used to distinguish between repeats of the same
                         type of form within a single study event. (optional)"]
   [:item-groups :ref :many :comp]

   (func create
     ""
     [db tid study-event-id oid]
     (if-let [study-event (:db/id (d/entity db [:study-event/id study-event-id]))]
       (if-not (d/q '[:find ?f .
                      :in $ ?se ?oid
                      :where
                      [?se :study-event/forms ?f]
                      [?f :form/oid ?oid]]
                    db study-event oid)
         [{:db/id tid
           :form/id (d/squuid)
           :form/oid oid}
          [:db/add study-event :study-event/forms tid]]
         (throw (ex-info (format "Duplicate form %s in study-event %s."
                                 oid (:study-event/oid study-event))
                         {:type :duplicate
                          :study-event-id study-event-id
                          :form-oid oid})))
       (throw (ex-info "Study event not found." {:type :study-event-not-found}))))

   (func create-repeating
     ""
     [db tid study-event-id oid repeat-key]
     (if-let [study-event (:db/id (d/entity db [:study-event/id study-event-id]))]
       (if-not (d/q '[:find ?f .
                      :in $ ?se ?oid ?rk
                      :where
                      [?se :study-event/forms ?f]
                      [?f :form/oid ?oid]
                      [?f :form/repeat-key ?rk]]
                    db study-event oid repeat-key)
         [{:db/id tid
           :form/id (d/squuid)
           :form/oid oid
           :form/repeat-key repeat-key}
          [:db/add study-event :study-event/forms tid]]
         (throw (ex-info (format "Duplicate form %s with repeat key %s in study-event %s."
                                 oid repeat-key (:study-event/oid study-event))
                         {:type :duplicate
                          :study-event-id study-event-id
                          :form-oid oid
                          :repeat-key repeat-key})))
       (throw (ex-info "Study event not found." {:type :study-event-not-found}))))])

(def item-group
  "An item-group is a closely related set of items that are generally analyzed
  together. (item-groups are sometimes referred to as records and are associated
  with panels or tables.) item-groups are aggregated into forms."
  [[:id :uuid :unique]
   [:oid :string :index]
   [:repeat-key :string "A key used to distinguish between repeats of the same
                         type of item-group within a single form. (optional)"]
   [:items :ref :many :comp]

   (func create
     ""
     [db tid form-id oid]
     (if-let [form (:db/id (d/entity db [:form/id form-id]))]
       (if-not (d/q '[:find ?ig .
                      :in $ ?f ?oid
                      :where
                      [?f :form/item-groups ?ig]
                      [?ig :item-group/oid ?oid]]
                    db form oid)
         [{:db/id tid
           :item-group/id (d/squuid)
           :item-group/oid oid}
          [:db/add form :form/item-groups tid]]
         (throw (ex-info (format "Duplicate item group %s in form %s."
                                 oid (:form/oid form))
                         {:type :duplicate
                          :form-id form-id
                          :item-group-oid oid})))
       (throw (ex-info "Form not found." {:type :form-not-found}))))

   (func create-repeating
     ""
     [db tid form-id oid repeat-key]
     (if-let [form (:db/id (d/entity db [:form/id form-id]))]
       (if-not (d/q '[:find ?ig .
                      :in $ ?f ?oid ?rk
                      :where
                      [?f :form/item-groups ?ig]
                      [?ig :item-group/oid ?oid]
                      [?ig :item-group/repeat-key ?rk]]
                    db form oid repeat-key)
         [{:db/id tid
           :item-group/id (d/squuid)
           :item-group/oid oid
           :item-group/repeat-key repeat-key}
          [:db/add form :form/item-groups tid]]
         (throw (ex-info (format "Duplicate item group %s with repeat key %s in form %s."
                                 oid repeat-key (:form/oid form))
                         {:type :duplicate
                          :form-id form-id
                          :item-group-oid oid
                          :repeat-key repeat-key})))
       (throw (ex-info "Form not found." {:type :form-not-found}))))])

(def item
  "An item is an individual clinical data item, such as a single systolic blood
  pressure reading. Items are collected together into item-groups."
  [[:id :uuid :unique]
   [:oid :string :index]
   [:string-value :string]
   [:integer-value :long]
   [:float-value :float]
   [:datetime-value :instant]

   (func create
     "Data-type is one of :string, :integer, :float or :datetime"
     [db tid item-group-id oid data-type value]
     (if-let [item-group (:db/id (d/entity db [:item-group/id item-group-id]))]
       (if-not (d/q '[:find ?i .
                      :in $ ?ig ?oid
                      :where
                      [?ig :item-group/items ?i]
                      [?i :item/oid ?oid]]
                    db item-group oid)
         [{:db/id tid
           :item/id (d/squuid)
           :item/oid oid
           (keyword "item" (str (name data-type) "-value")) value}
          [:db/add item-group :item-group/items tid]]
         (throw (ex-info (format "Duplicate item %s in item group %s."
                                 oid (:item-group/oid item-group))
                         {:type :duplicate
                          :item-group-id item-group-id
                          :item-oid oid})))
       (throw (ex-info "Item group not found." {:type :item-group-not-found}))))])

(defn load-schema
  "Loads the base schema in one transaction and derefs the result."
  [conn]
  (->> (concat (build-tx {:event event
                          :study study
                          :subject subject
                          :study-event study-event
                          :form form
                          :item-group item-group
                          :item item})
               (mapv make-part [{:db/ident :oids}
                                {:db/ident :studies}
                                {:db/ident :subjects}
                                {:db/ident :study-events}
                                {:db/ident :forms}
                                {:db/ident :item-groups}
                                {:db/ident :items}]))
       (d/transact conn)
       (deref)))
