(ns aufi.ring.middlewares.logging
  (:require [ronda.routing :as routing]
            [clojure.tools.logging :as log])
  (:import [org.slf4j MDC]))

;; ## Helpers

(defn- nano-time
  []
  (System/nanoTime))

(defn- pool-metrics
  [pools]
  (->> (for [[pool-key {:keys [threads queue queue-counter]}] pools
             :when (#{:download :upload} pool-key)
             :let [mdc-key (str (name pool-key) "-pool-capacity")
                   capacity (- 1.0 (/ @queue-counter (+ threads queue)))]]
         [mdc-key (format "%.3f" capacity)])
       (into {})))

(defn- context->mdc-map
  [{:keys [headers scheme request-method uri query-string pools]}
   {:keys [status]}
   execution-time]
  (merge
    {"user-agent"     (get headers "user-agent")
     "schema"         (name scheme)
     "request-method" (name request-method)
     "uri"            uri
     "query-string"   query-string
     "content-length" (get headers "content-length")
     "status"         (str status)
     "execution-time" (str execution-time)}
    (pool-metrics pools)))

(defn- with-mdc*
  [context f]
  (doseq [[name value] context]
    (MDC/put (str name) (str value)))
  (try
    (f)
    (finally
      (doseq [[name _] context]
        (MDC/remove (str name))))))

;; ## Middleware

(defn- response-or-500
  [handler request]
  (try
    (handler request)
    (catch Throwable t
      (log/error t (pr-str request))
      {:status 500, :body "nix aufi."})))

(defn- format-for-logging
  [{:keys [request-method uri query-string] :as request}
   {:keys [status] :or {status 404}}
   delta]
  (let [endpoint-id (or (routing/endpoint request) :unknown)
        method (.toUpperCase (name request-method))]
    (format "[ring] [%s] [%s] %s %s%s [%.3fs]"
            (name endpoint-id)
            status
            method
            uri
            (if query-string
              (str "?" query-string)
              "")
            delta)))

(defn wrap-logging
  [handler]
  (fn [request]
    (let [start (nano-time)
          response (response-or-500 handler request)
          delta (/ (quot (- (nano-time) start) 1e6) 1e3)
          endpoint-id (or (routing/endpoint request) :unknown)]
      (with-mdc*
        (context->mdc-map request response delta)
        #(log/info (format-for-logging request response delta)))
      response)))
