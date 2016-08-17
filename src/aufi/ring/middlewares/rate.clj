(ns aufi.ring.middlewares.rate
  (:require [clojure.tools.logging :refer [warnf]]))

;; ## Initialize

(def ^:private defaults
  {:period 5000
   :rate   10})

(defn- initialize-counter
  "Initialize counter structure."
  [opts]
  (atom
    (merge
      defaults
      (select-keys opts [:period :rate])
      {:since 0 :count 0})))

;; ## Count

(defn- quot-timestamp
  "Generate timestamp divided by the given number of milliseconds."
  [factor]
  (long (quot (System/nanoTime) (* factor 1e6))))

(defn- count-request
  "Count a single request within a given period."
  [{:keys [since period] :as counter}]
  (let [t (quot-timestamp period)]
    (if (not= t since)
      (assoc counter :since t :count 1)
      (update-in counter [:count] inc))))

(defn- rate-exceeded?
  "Check whether the set rate was exceeded."
  [{:keys [count rate]}]
  (> count rate))

;; ## Response

(defn- retry-after-seconds
  "Generate 'Retry-After' second count."
  [{:keys [since period]}]
  (max
    (- (* (inc since) (quot period 1000))
       (quot-timestamp 1000))
    1))

(defn- retry-after-response
  "Generate response indicating a period after which to retry."
  [counter]
  (let [secs (retry-after-seconds counter)]
    {:status 429
     :headers {"retry-after" secs}
     :body (format "rate limit exceeded. (retry in %d seconds)" secs)}))

;; ## Handler

(defn- log-rate-exceeded!
  "Log a message indicating an exceeded rate limit."
  [{:keys [count rate period]} request]
  (warnf "rate limit exceeded (%d/%d per last %dms): %s"
         count
         rate
         period
         (:uri request)))

(defn- maybe-rate-limit-exceeded
  "Update the request counter and return an HTTP 429 response
   if the rate limit was exceeded."
  [counter request]
  (let [counter' (swap! counter count-request)]
    (when (rate-exceeded? counter')
      (log-rate-exceeded! counter' request)
      (retry-after-response counter'))))

(defn wrap-rate
  "Wrap the given handler with rate limiting."
  [handler {:keys [rate period] :as opts}]
  (let [counter (initialize-counter opts)]
    (fn [request]
      (or (maybe-rate-limit-exceeded counter request)
          (handler request)))))
