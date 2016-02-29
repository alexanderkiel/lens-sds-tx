(ns lens.handlers.test-util
  (:require [lens.handlers.core :refer [get-command]]))

(defn perform-command [agg {:keys [name params] :as command}]
  (if-let [cmd (get-command name)]
    (cmd agg command params)
    (throw (Exception. (str "Command " name " not found.")))))
