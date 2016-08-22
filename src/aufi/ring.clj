(ns aufi.ring
  (:require [aufi.ring
             [download :as download]
             [health :as health]
             [middlewares :as middlewares]
             [pools :as pools]
             [upload :as upload]]
            [ronda.routing :as routing]
            [ronda.routing
             [bidi :as bidi]
             [schema :as schema]]
            [peripheral.core :refer [defcomponent]]))

;; ## Routes

(def routes
  (-> (bidi/descriptor
        ["/" {"v1/images" {""                              :upload
                           ["/" :id [#"(/.*)?" :filename]] :download}
              "_status" :health}])
      (routing/enable-middlewares
        :download [:params :cache :etag]
        :upload   [:rate]
        :health   [:params])
      (schema/enable-schema :download download/schema)
      (schema/enable-schema :upload   upload/schema)
      (schema/enable-schema :health   health/schema)))

;; ## Handler Component

(def ^:private default-pools
  {:health   {:threads 1, :queue 16}
   :download {:threads 8, :queue 100}
   :upload   {:threads 4, :queue 16}})

(defn- start-pools!
  [httpd-opts]
  (let [pools (merge default-pools (:pools httpd-opts))]
    (->> (for [[k pool-opts] pools]
           [k (pools/make! k pool-opts)])
         (into {}))))

(defn- stop-pools!
  [pools]
  (doseq [pool (vals pools)]
    (pools/shutdown! pool)))

(defcomponent AufiHandler [ring-handler httpd-opts]
  :pools
  (start-pools! httpd-opts)
  #(stop-pools! %)

  :handler
  (-> ring-handler
      (pools/wrap pools)
      (middlewares/wrap-async)
      (routing/wrap-routing routes))

  clojure.lang.IFn
  (invoke [_ request]
    (handler request)))

;; ## Handler

(defn- generate-ring-handler
  [{:keys [config] :as system}]
  {:pre [(:image-store system)
         (:image-resizer system)]}
  (let [ring-opts (:ring config)]
    (-> (routing/compile-endpoints
          {:upload   #(upload/post! system %)
           :health   #(health/get! system %)
           :download #(download/get! system %)})
        (middlewares/wrap ring-opts))))

(defn generate-handler
  [system]
  (map->AufiHandler
    {:ring-handler (generate-ring-handler system)
     :httpd-opts   (-> system :config :httpd)}))
