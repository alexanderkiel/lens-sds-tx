(ns lens.server
  (:require [com.stuartsierra.component :refer [Lifecycle]]
            [lens.app :refer [app]]
            [lens.logging :refer [info]]
            [org.httpkit.server :refer [run-server]]))

(defrecord Server [port thread stop-fn]
  Lifecycle
  (start [server]
    (info {:component "Server" :msg (str "Start server on port " port)})
    (let [handler (app server) opts server]
      (assoc server :stop-fn (run-server handler opts))))
  (stop [server]
    (info {:component "Server" :msg "Stop server"})
    (stop-fn)
    (assoc server :stop-fn nil)))

(defn new-server []
  (map->Server {}))
