(ns lens.logging
  (:require [clojure.tools.logging :refer [log]])
  (:import [ch.qos.logback.classic Level Logger]
           [org.slf4j LoggerFactory]))

(defmacro trace
  "Trace level logging using data."
  [data]
  `(log :trace (pr-str ~data)))

(defmacro debug
  "Debug level logging using data."
  [data]
  `(log :debug (pr-str ~data)))

(defmacro info
  "Trace level logging using data."
  [data]
  `(log :info (pr-str ~data)))

(defmacro warn
  "Warn level logging using data."
  ([data]
   `(log :warn (pr-str ~data)))
  ([throwable data]
   `(log :warn ~throwable (pr-str ~data))))

(defmacro error
  "Error level logging using data."
  ([data]
   `(log :error (pr-str ~data)))
  ([throwable data]
   `(log :error ~throwable (pr-str ~data))))

(defn set-level! [^String logger level]
  (let [logger ^Logger (LoggerFactory/getLogger logger)]
    (.setLevel logger (Level/toLevel (name level)))))
