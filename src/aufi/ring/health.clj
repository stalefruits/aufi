(ns aufi.ring.health
  (:require [aufi.system.protocols :as p]
            [clojure.tools.logging :refer [warnf]]
            [schema.core :as s]))

;; ## Schema

(def schema
  {:get {:params    {(s/optional-key :timeout)            s/Int
                     (s/optional-key :download-capacity) Double
                     (s/optional-key :upload-capacity)   Double}
         :responses {200 {}
                     503 {}
                     504 {}}}})

;; ## Handler

(defn- read-pool-metrics
  [pools k]
  (when-let [{:keys [queue-counter queue threads]} (get pools k)]
    (let [max-count (+ queue threads)
          active-count @queue-counter]
      {:queue-key k
       :max-count max-count
       :active-count active-count
       :capacity-left (- 1 (/ active-count max-count))})))

(defn- not-enough-capacity?
  [{:keys [capacity-left]} threshold]
  (< capacity-left threshold))

(defn- throw-capacity!
  [{:keys [queue-key max-count active-count]} threshold]
  (throw
    (IllegalStateException.
      (format "queue %s [%d/%d requests] has lower capacity than %.2f."
              queue-key active-count max-count threshold))))

(defn- check-queue!
  [pools k threshold]
  (when threshold
    (when-let [metrics (read-pool-metrics pools k)]
      (when (not-enough-capacity? metrics threshold)
        (throw-capacity! metrics threshold)))))

(defn- generate-check-future
  [image-store pools {:keys [download-capacity upload-capacity]}]
  (future
    (try
      (or (check-queue! pools :download download-capacity)
          (check-queue! pools :upload   upload-capacity)
          (p/check-health! image-store))
      (catch Throwable t t))))

(defn- maybe-timed-out
  [check-future {:keys [timeout] :or {timeout 1000}}]
  (let [timeout (if (pos? timeout)
                  timeout
                  1000)]
    (when-let [result (deref check-future timeout ::timeout)]
      (when (= result ::timeout)
        (future-cancel check-future)
        {:status 504
         :body "health check timed out."}))))

(defn- maybe-exception
  [check-future]
  (when-let [result @check-future]
    (when (instance? Throwable result)
      (warnf result "health check failed.")
      {:status 503
       :body "health check failed."})))

(defn- healthy
  []
  {:status 200
   :body "service is healthy."})

(defn get!
  [{:keys [image-store] :as system} {:keys [params pools]}]
  (let [check-future (generate-check-future image-store pools params)]
    (or (maybe-timed-out check-future params)
        (maybe-exception check-future)
        (healthy))))
