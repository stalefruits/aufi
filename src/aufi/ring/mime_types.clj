(ns aufi.ring.mime-types
  (:require [schema.core :as s])
  (:import [org.apache.tika Tika]))

;; ## Detection

(defonce detector (Tika.))

;; ## Allowed MIME Types

(def mime-types
  #{"image/jpeg" "image/png" "image/gif"})

(def normalise-mime-type
  (let [table {"image/pjpeg" "image/jpeg"
               "image/jpg"   "image/jpeg"
               "image/x-png" "image/png"}]
    #(get table % %)))

(def mime-types-schema
  (apply s/enum mime-types))

;; ## Process

(defn read-mime
  [^bytes data]
  (normalise-mime-type
    (.detect detector data)))

(defn allowed?
  [mime-type]
  (contains? mime-types mime-type))
