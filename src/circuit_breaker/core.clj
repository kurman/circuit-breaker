(ns circuit-breaker.core
  (:use
    [slingshot.slingshot])
  (:require
    [clj-time.core                  :as time]
    [circuit-breaker.concurrent-map :as concurrent-map]))

(def circuit-breakers-counters (concurrent-map/new))
(def circuit-breakers-config   (concurrent-map/new))
(def circuit-breakers-open     (concurrent-map/new))

(defn- failure-threshold [circuit-name]
  (:threshold (concurrent-map/get circuit-breakers-config circuit-name)))

(defn- timeout-in-seconds [circuit-name]
  (:timeout (concurrent-map/get circuit-breakers-config circuit-name)))

(defn- time-since-broken [circuit-name]
  (concurrent-map/get circuit-breakers-open circuit-name))

(defn- exception-counter [circuit-name]
  (or (concurrent-map/get circuit-breakers-counters circuit-name) 0))

(defn- inc-counter [circuit-name]
  (let [circuit-count (or (concurrent-map/get circuit-breakers-counters circuit-name) 0)]
    (concurrent-map/put circuit-breakers-counters circuit-name (inc circuit-count))))

(defn failure-count [circuit-name]
  (exception-counter circuit-name))

(defn- record-opening! [circuit-name]
  (concurrent-map/put circuit-breakers-open circuit-name (time/now)))

(defn- breaker-open? [circuit-name]
  (boolean (time-since-broken circuit-name)))

(defn- timeout-exceeded? [circuit-name]
  (> (time/in-secs (time/interval (time-since-broken circuit-name) (time/now))) (timeout-in-seconds circuit-name)))

(defn record-failure! [circuit-name]
  (inc-counter circuit-name)
  (when (> (failure-count circuit-name) (failure-threshold circuit-name))
    (record-opening! circuit-name)))

(defn record-success! [circuit-name]
  (concurrent-map/remove circuit-breakers-open circuit-name)
  (concurrent-map/put circuit-breakers-counters circuit-name 0))

(defn- closed-circuit-path [circuit-name method-that-might-error]
  (try+
    (let [result (method-that-might-error)]
      (record-success! circuit-name)
      result)
    (catch Object _
      (record-failure! circuit-name)
      (throw+))))

(defn reset-all-circuit-counters! []
  (concurrent-map/clear circuit-breakers-counters))

(defn reset-all-circuits! []
  (reset-all-circuit-counters!)
  (concurrent-map/clear circuit-breakers-config)
  (concurrent-map/clear circuit-breakers-open))

(defn tripped? [circuit-name]
  (and (breaker-open? circuit-name)
       (not (timeout-exceeded? circuit-name))))

(defn defncircuitbreaker [circuit-name settings]
  (concurrent-map/put circuit-breakers-counters circuit-name 0)
  (concurrent-map/put circuit-breakers-config circuit-name settings))

(defn wrap-with-circuit-breaker [circuit-name method-that-might-error]
 (when-not (tripped? circuit-name)
    (closed-circuit-path circuit-name method-that-might-error)))

(defn with-circuit-breaker [circuit {:keys [tripped connected]}]
  (if (tripped? circuit)
    (tripped)
    (connected)))
