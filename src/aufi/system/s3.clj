(ns aufi.system.s3
  (:require [aufi.system.protocols :as p]
            [peripheral.core :refer [defcomponent]]
            [clojure.tools.logging :as log]
            [aws.sdk.s3 :as s3])
  (:import [java.util UUID]
           [java.io ByteArrayInputStream]))

;; ## Helpers

(defn- random-id
  []
  (str (UUID/randomUUID)))

(defn- object-path
  [id]
  (str "images/" id))

;; ## I/O

(defn- put-s3!
  [{:keys [credentials bucket]} id {:keys [stream length metadata]}]
  (let [metadata (merge metadata {:content-length length})
        key (object-path id)]
    (s3/put-object credentials bucket key stream metadata)))

(defn- get-s3!
  [{:keys [credentials bucket]} id]
  (try
    (let [{:keys [content metadata]} (s3/get-object
                                       credentials
                                       bucket
                                       (object-path id))]
      {:stream   content
       :length   (:content-length metadata)
       :metadata metadata})
    (catch com.amazonaws.services.s3.model.AmazonS3Exception ex
      (if (not= 404 (.getStatusCode ex))
        (throw ex)))))

(defn- verify-s3!
  [{:keys [credentials bucket]}]
  (when-not (s3/bucket-exists? credentials bucket)
    (throw
      (IllegalStateException.
        (format "S3 bucket missing: %s" bucket)))))

(defn- warmup!
  [s3-config]
  (log/debug "warming up S3 ...")
  (try
    (dotimes [n 5]
      (verify-s3! s3-config))
    (catch Throwable _))
  (log/debug "S3 is warmed up."))

;; ## Component

(defcomponent S3ImageStore [config]
  :s3-config (:s3 config)
  :on/started (warmup! s3-config)

  p/ImageStore
  (check-health! [_]
    (verify-s3! s3-config))
  (put-image! [_ image-data]
    (let [id (random-id)]
      (put-s3! s3-config id image-data)
      id))
  (retrieve-image! [_ image-id]
    (get-s3! s3-config image-id)))
