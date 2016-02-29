(ns lens.schema
  "Functions to load the schema.

  Usage:

    (load-schema conn)"
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

(def agg
  "An aggregate."
  [[:version :long]

   (func inc-version
     "Increments the version of an aggregate using CAS."
     [_ agg old-version]
     [[:db.fn/cas agg :agg/version old-version (inc old-version)]])])

(def cmd
  "The command which caused a transaction. The attributes are used on the
  transaction not on a separate entity."
  [[:id :uuid :unique]
   [:name :keyword :index]
   [:sub :string :index "The subject which issued the command."]

   (func create
     "Creates an event."
     [_ id name sub]
     [{:db/id (d/tempid :db.part/tx)
       :cmd/id id
       :cmd/name name
       :cmd/sub sub}])])

(def event
  "An event is something which happend in a system."
  [[:name :keyword :index]
   [:tx :ref]

   (func create
     "Creates an event."
     [_ name]
     [{:db/id (d/tempid :events)
       :event/name name
       :event/tx (d/tempid :db.part/tx)}])])

(def study
  "A clinical or epidemiological study."
  [[:id :uuid :unique]
   [:oid :string :id]
   [:subjects :ref :many :comp]

   (func create
     "Creates a study."
     [db tid id oid]
     (if-not (d/entity db [:study/oid oid])
       [{:db/id tid
         :agg/version 0
         :study/id id
         :study/oid oid}]
       (throw (ex-info "Duplicate!" {:type :duplicate}))))])

(def subject
  "A subject is a patient participating in the study."
  [[:id :uuid :unique]
   [:key :string :index "The id of the subject unique within its study."]
   [:study-events :ref :many :comp]])

(def study-event
  "A study event is a reusable package of forms usually corresponding to a study
  data-collection event."
  [[:id :uuid :unique]
   [:oid :string :index]
   [:repeat-key :string "A key used to distinguish between repeats of the same
                        type of study event for a single subject. (optional)"]
   [:forms :ref :many :comp]])

(def form
  "A form is analogous to a page in a paper CRF book or electronic CRF screen. A
  form generally collects a set of logically and temporally related information.
  A series of forms is collected as part of a study event."
  [[:id :uuid :unique]
   [:oid :string :index]
   [:repeat-key :string "A key used to distinguish between repeats of the same
                         type of form within a single study event. (optional)"]
   [:item-groups :ref :many :comp]])

(def item-group
  "An item-group is a closely related set of items that are generally analyzed
  together. (item-groups are sometimes referred to as records and are associated
  with panels or tables.) item-groups are aggregated into forms."
  [[:id :uuid :unique]
   [:oid :string :index]
   [:repeat-key :string "A key used to distinguish between repeats of the same
                         type of item-group within a single form. (optional)"]
   [:items :ref :many :comp]])

(def item
  "An item is an individual clinical data item, such as a single systolic blood
  pressure reading. Items are collected together into item-groups."
  [[:id :uuid :unique]
   [:oid :string :index]
   [:string-value :string]
   [:integer-value :long]
   [:float-value :float]
   [:datetime-value :instant]])

(defn load-schema
  "Loads the base schema in one transaction and derefs the result."
  [conn]
  (->> (concat (build-tx {:agg agg
                          :cmd cmd
                          :event event
                          :study study
                          :subject subject
                          :study-event study-event
                          :form form
                          :item-group item-group
                          :item item})
               (mapv make-part [{:db/ident :events}
                                {:db/ident :studies}
                                {:db/ident :subjects}
                                {:db/ident :study-events}
                                {:db/ident :forms}
                                {:db/ident :item-groups}
                                {:db/ident :items}]))
       (d/transact conn)
       (deref)))
