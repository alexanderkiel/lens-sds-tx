(ns lens.util
  (:require [clojure.core.async :refer [<! go-loop timeout]]
            [clojure.string :as str]
            [schema.core :as s :refer [Int]]
            [datomic.api :as d]
            [lens.logging :refer [trace]])
  (:import [java.util.concurrent ExecutionException]))

(defn unwrap-execution-exception [e]
  (if (instance? ExecutionException e)
    (.getCause e)
    e))

(defn error-type
  "Returns the error type of exceptions from transaction functions or nil."
  [e]
  (:type (ex-data (unwrap-execution-exception e))))

(defn parse-long [s]
  (Long/parseLong s))

(def Ms
  "Duration in milliseconds."
  s/Num)

(s/defn duration :- Ms
  "Returns the duaration in milliseconds from a System/nanoTime start point."
  [start :- Int]
  (/ (double (- (System/nanoTime) start)) 1000000.0))

;; ---- Datomic ---------------------------------------------------------------

(defn transact [conn tx-data]
  (let [start (System/nanoTime)]
    (try
      (deref (d/transact conn tx-data))
      (catch Exception e
        (throw (unwrap-execution-exception e)))
      (finally
        (trace {:type :transact :tx-data tx-data :took (duration start)})))))

(defn create
  "Submits a transaction which creates an entity.

  The fn is called with a temp id from partition and should return the tx-data
  which is submitted.

  Returns the created entity."
  [conn partition fn]
  (let [tid (d/tempid partition)
        tx-result (transact conn (fn tid))
        db (:db-after tx-result)]
    (d/entity db (d/resolve-tempid db (:tempids tx-result) tid))))

(defn deref-tx-res [f]
  (try
    (deref f)
    (catch ExecutionException e (.getCause e))
    (catch Exception e e)))

;; ---- Schema ----------------------------------------------------------------

(def NonBlankStr
  (s/constrained s/Str (complement str/blank?) 'non-blank?))

(def PosInt
  (s/constrained s/Int pos? 'pos?))

(def NonNegInt
  (s/constrained s/Int (comp not neg?) 'non-neg?))
