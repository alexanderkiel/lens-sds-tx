(ns lens.cache
  (:require [clojure.core.cache :as c :refer [defcache CacheProtocol]]))

(defcache ClosingLRUCache [cache lru tick limit close-fn]
  CacheProtocol
  (lookup [_ item]
          (get cache item))
  (lookup [_ item not-found]
          (get cache item not-found))
  (has? [_ item]
        (contains? cache item))
  (hit [_ item]
       (let [tick+ (inc tick)]
         (ClosingLRUCache. cache
                           (if (contains? cache item)
                             (assoc lru item tick+)
                             lru)
                           tick+
                           limit
                           close-fn)))
  (miss [_ item result]
        (let [tick+ (inc tick)]
          (if (>= (count lru) limit)
            (let [k (if (contains? lru item)
                      item
                      (first (peek lru)))                   ;; minimum-key, maybe evict case
                  _ (some-> (get cache k) close-fn)
                  c (-> cache (dissoc k) (assoc item result))
                  l (-> lru (dissoc k) (assoc item tick+))]
              (ClosingLRUCache. c l tick+ limit close-fn))
            (ClosingLRUCache. (assoc cache item result)     ;; no change case
                              (assoc lru item tick+)
                              tick+
                              limit
                              close-fn))))
  (evict [this key]
         (let [v (get cache key ::miss)]
           (if (= v ::miss)
             this
             (ClosingLRUCache. (dissoc cache key)
                               (dissoc lru key)
                               (inc tick)
                               limit
                               close-fn))))
  (seed [_ base]
        (ClosingLRUCache. base
                          (#'c/build-leastness-queue base limit 0)
                          0
                          limit
                          close-fn))
  Object
  (toString [_]
            (str cache \, \space lru \, \space tick \, \space limit)))

(defn closing-lru-cache-factory
  "Same as lru-cache-factory but with optional close-fn."
  [base & {threshold :threshold close-fn :close-fn :or {threshold 32 close-fn identity}}]
  {:pre [(number? threshold) (< 0 threshold)
         (map? base)]}
  (clojure.core.cache/seed (ClosingLRUCache. {} (clojure.data.priority-map/priority-map) 0 threshold close-fn) base))
