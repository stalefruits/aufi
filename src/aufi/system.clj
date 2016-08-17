(ns aufi.system
  (:require [aufi.system
             [config :as cfg]
             [httpd :as httpd]
             [resizer :as rs]
             [s3 :as s3]]
            [peripheral.core :refer [defsystem+]]))

;; ## System Record

(defsystem+ Aufi []
  :config        []
  :httpd         [:config :image-store :image-resizer]
  :image-store   [:config]
  :image-resizer [])

;; ## Constructor

(defn- defaults
  [handler-fn]
  {:config        (cfg/map->Configuration {})
   :image-store   (s3/map->S3ImageStore {})
   :image-resizer (rs/map->Resizer {})
   :httpd         (httpd/map->HTTPD {:handler-fn handler-fn})})

(defn file-config
  [config-path]
  {:pre [(string? config-path)]}
  (cfg/map->Configuration
    {:path config-path}))

(defn generate
  [handler-fn & [overrides]]
  (map->Aufi
    (merge
      (defaults handler-fn)
      overrides)))
