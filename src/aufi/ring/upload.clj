(ns aufi.ring.upload
  (:require [aufi.system.protocols :as p]
            [aufi.ring.mime-types :as mime]
            [ronda.routing :as routing]
            [schema.core :as s]
            [clojure.java.io :as io])
  (:import [java.io
            ByteArrayInputStream
            ByteArrayOutputStream]))

;; ## Read

(defn- content-length-exceeded?
  [{:strs [content-length]} max-length]
  (some-> content-length (> max-length)))

(defn- stream->bytes
  [stream max-length length-exceeded-value]
  (let [buf (byte-array 8192)]
    (with-open [in (io/input-stream stream)
                out (ByteArrayOutputStream.)]
      (loop [length 0]
        (let [read-count (.read in buf)]
          (if (neg? read-count)
            (.toByteArray out)
            (let [length' (+ length read-count)]
              (.write out buf 0 read-count)
              (if (> length' max-length)
                length-exceeded-value
                (recur length')))))))))

(defn- request->bytes
  [{:keys [headers body]} max-length length-exceeded-value]
  (if (content-length-exceeded? headers max-length)
    length-exceeded-value
    (stream->bytes body max-length length-exceeded-value)))

;; ## Responses

(defn data-too-long
  [max-length]
  {:status 413,
   :body (format "data is too long (max: %d bytes)." max-length)})

(defn disallowed-mime-type
  [mime-type]
  {:status 415,
   :body (format "MIME type '%s' is not allowed." mime-type)})

(defn success
  [request id]
  (let [href (routing/href request :download {:id id, :filename ""})]
    {:status  201
     :headers {"location" href}}))

;; ## Schema/Handler

(def schema
  {:post {:body      java.io.InputStream
          :headers   {(s/optional-key "content-length") s/Int}
          :responses {201 {:headers {"location" s/Str}}
                      413 {}
                      415 {}}}})

(defn post!
  [{:keys [image-store config]} request]
  (let [max-length (get-in config [:ring :upload :max-length] (* 400 1024))
        image-bytes (request->bytes request max-length ::too-long)]
    (if (= image-bytes ::too-long)
      (data-too-long max-length)
      (let [mime-type (mime/read-mime image-bytes)]
        (if (mime/allowed? mime-type)
          (let [id (p/put-image!
                     image-store
                     {:stream (ByteArrayInputStream. image-bytes)
                      :metadata {:content-type mime-type}
                      :length (alength image-bytes)})]
            (success request id))
          (disallowed-mime-type mime-type))))))
