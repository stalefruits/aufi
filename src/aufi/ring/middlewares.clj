(ns aufi.ring.middlewares
  (:require [aufi.ring.middlewares
             [cors :refer [wrap-cors]]
             [etag :refer [wrap-etag]]
             [head :refer [wrap-head]]
             [logging :refer [wrap-logging]]
             [rate :refer [wrap-rate]]]
            [ronda.routing :as routing]
            [ronda.routing.schema :refer [wrap-schemas]]
            [ring.middleware.params :refer [wrap-params]]))

;; ## Custom Middlewares

(defn- wrap-not-found
  [handler]
  (fn [request]
    (or (handler request)
        {:status 404})))

(defn- wrap-plain-map
  "aleph request does not supoort 'select-keys'."
  [handler]
  (fn [request]
    (handler (into {} request))))

(defn- wrap-cache
  [handler {:keys [max-age] :or {max-age 5}}]
  (let [cache-control (format "max-age=%d" max-age)]
    (fn [request]
      (let [{:keys [status] :as response} (handler request)]
        (if (<= 200 status 304)
          (assoc-in response [:headers "cache-control"] cache-control)
          response)))))

;; ## Middleware Stack

(defn wrap
  "Wrap handler with all necessary middlewares."
  [handler {:keys [cache rate etag]}]
  (-> handler
      (wrap-schemas)
      (routing/routed-middleware :params wrap-params)
      (routing/routed-middleware :etag  #(wrap-etag % etag))
      (routing/routed-middleware :cache #(wrap-cache % cache))
      (routing/routed-middleware :rate  #(wrap-rate % rate))
      (wrap-plain-map)
      (wrap-not-found)
      (wrap-cors)
      (wrap-head)))

(defn wrap-async
  "Wrap handler with all async-capable middlewares."
  [handler]
  (-> handler
      (wrap-logging)))
