(ns aufi.system.config
  (:require [aufi.system.deep-merge :refer [deep-merge]]
            [peripheral.core :refer [defcomponent]]
            [clojure.tools.reader.edn :as edn]
            [clojure.tools.logging :as log]
            [clojure.java.io :as io]
            [schema.core :as s])
  (:import [com.amazonaws.auth
            AWSCredentials
            DefaultAWSCredentialsProviderChain]))

;; ## Obfuscation

(deftype AWSCreds [access-key secret-key]
  clojure.lang.ILookup
  (valAt [_ key not-found]
    (case key
      :access-key access-key
      :secret-key secret-key
      not-found))
  (valAt [this key]
    (get this key nil)))

(defn- aws-credentials
  []
  (let [chain (DefaultAWSCredentialsProviderChain.)
        credentials (.getCredentials chain)]
    (AWSCreds.
      (.getAWSAccessKeyId credentials)
      (.getAWSSecretKey credentials))))

;; ## Schema

(def ^:private configuration-schema
  {:s3    {:bucket s/Str}
   :httpd {:port s/Int
           :bind s/Str}
   :ring  {:cache    {:max-age s/Int}
           :download {:max-width  s/Int
                      :max-height s/Int}
           :upload   {:max-length s/Int}}})

(def defaults
  {:httpd {:port 9876
           :bind "0"}
   :ring  {:cache    {:max-age 300}
           :download {:max-width  1280
                      :max-height 1280}
           :upload   {:max-length (* 400 1024)}}})

(defn- post-process
  [config]
  (assoc-in config [:s3 :credentials] (aws-credentials)))

(defn- read-env-configuration
  []
  (some-> (System/getenv "EDN_CONFIG")
          (edn/read-string)))

(defn- read-file-configuration
  [path]
  (some-> (or (io/resource path)
              (io/file path))
          (slurp :encoding "UTF-8")
          (edn/read-string)))

(defn- provide
  [& [path]]
  (->> (if path
         (read-file-configuration path)
         (read-env-configuration))
       (deep-merge defaults)
       (s/validate configuration-schema)
       (post-process)))

;; ## Component

(defcomponent Configuration [path]
  :on/start
  (log/debug
    "loading configuration from"
    (or (some-> path pr-str) "environment")
    "...")


  :peripheral/started
  (fn [this]
    (let [{:keys [ring s3] :as result} (merge this (provide path))
          {:keys [cache download upload]} ring
          {:keys [bucket]} s3]
      (log/debug "  cache:    " cache)
      (log/debug "  download: " download)
      (log/debug "  upload:   " upload)
      (log/debug "  S3 bucket:" bucket)
      (log/debug "configuration loaded.")
      result)))
