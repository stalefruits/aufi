(ns aufi.ring.pools
  (:require [aleph.flow :as flow]
            [manifold.deferred :as d]
            [ronda.routing :as routing]
            [clojure.tools.logging :as log])
  (:import [java.util.concurrent
            ExecutorService
            RejectedExecutionException
            TimeUnit]))

;; ## Pools

(defn make!
  [route-id {:keys [threads initial-threads queue]
             :as opts
             :or {queue 0}}]
  {:pre [(pos? threads) (>= queue 0)]}
  (log/debugf "[%s] initializing thread pool (threads: %s, queue size: %s) ..."
             (name route-id)
             threads
             queue)
  (let [executor (flow/fixed-thread-executor
                   threads
                   {:onto?                false
                    :initial-thread-count (or initial-threads threads)
                    :queue-length         queue})]
    (->> {:executor      executor
          :queue-counter (atom 0)
          :route-id      route-id}
         (merge opts))))

(defn shutdown!
  [{:keys [^ExecutorService executor]}]
  (future
    (try
      (.shutdown executor)
      (when-not (.awaitTermination executor 1000 TimeUnit/MILLISECONDS)
        (.shutdownNow executor))
      (catch Throwable t
        (log/errorf t "during pool shutdown.")))))

;; ## Helpers

(defn- make-thunk
  [{:keys [queue-counter]} deferred-response handler request]
  (bound-fn []
    (try
      (d/success! deferred-response (handler request))
      (catch Exception e
        (d/error! deferred-response e))
      (finally
        (swap! queue-counter dec)))))

(defn- handle-async!
  [{:keys [executor queue-counter] :as pool} handler request]
  (let [response (d/deferred)
        thunk (make-thunk pool response handler request)]
    (try
      (.execute ^ExecutorService executor thunk)
      (swap! queue-counter inc)
      response
      (catch RejectedExecutionException re
        (log/warnf re
                   "[pool] [%s] executor saturated."
                   (-> request
                       (routing/endpoint)
                       (name)))))))

(defn- make-unavailable-response
  [{:keys [retry-after] :or {retry-after 5}}]
  {:status 503
   :headers {"Retry-After" (str retry-after)}})

;; ## Middleware

(defn- pool-for
  [pools request]
  (when-let [route-id (routing/endpoint request)]
    (get pools route-id)))

(defn wrap
  [handler pools]
  (fn [request]
    (if-let [pool (pool-for pools request)]
      (let [request-with-pools (assoc request :pools pools)]
        (or (handle-async! pool handler request-with-pools)
            (make-unavailable-response pool)))
      (handler request))))
