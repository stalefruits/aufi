(ns aufi.test
  (:require [aufi.system.protocols :as protocols]
            [aufi.images.io :as images]
            [clojure.java.io :as io])
  (:import [java.awt.image BufferedImage]
           [java.io ByteArrayOutputStream]
           [java.util.concurrent.locks Lock ReentrantLock]))

;; ## Image Helpers

(defn generate-image-bytes
  "Generate a byte array representing an image with the given specs."
  [& [{:keys [width height format]
       :or {format "image/jpeg"
            width  50
            height 50}}]]
  (let [bi (BufferedImage. width height BufferedImage/TYPE_INT_RGB)]
    (images/image->bytes bi format)))

(defn dimensions
  "Read dimensions of a stream/byte array representing an image."
  [stream]
  (let [image (images/read-image stream)]
    [(.getWidth image) (.getHeight image)]))

;; ## Coercion Helpers

(defn as-bytes
  "Coerce stream/byte-array to byte-array."
  [in]
  (if (instance? java.io.InputStream in)
    (with-open [buf (ByteArrayOutputStream.)]
      (io/copy in buf)
      (.toByteArray buf))
    in))

(defn as-stream
  "Coerce to stream."
  [in]
  (io/input-stream in))

;; ## In-Memory ImageStore

(defrecord AtomImageStore [atom health-check-fn]
  protocols/ImageStore
  (check-health! [_]
    (when health-check-fn
      (health-check-fn)))
  (put-image! [_ data]
    (let [id (str (gensym))]
      (swap! atom assoc id (update data :stream as-bytes))
      id))
  (retrieve-image! [_ id]
    (some-> (get @atom id)
            (update :stream as-stream))))

(defn dummy-image-store
  "Create dummy image store."
  [& [health-check-fn]]
  (->AtomImageStore (atom {}) health-check-fn))

(defn dummy-resizer
  [log-atom]
  (reify protocols/ImageResizer
    (resize-image! [_ image width height type]
      (swap! log-atom (fnil conj []) [:resize type width height])
      image)))

;; ## Lock Helpers

(defn lock
  []
  {:lock (ReentrantLock.)})

(defn lock!
  [threads]
  {:lock
   (doto (ReentrantLock.)
     (.lock))})

(defn acquire!
  [{:keys [lock]}]
  (.lock ^Lock lock))

(defn release!
  [{:keys [lock]}]
  (.unlock ^Lock lock))

;; ## Locking Image Store

(defrecord LockingImageStore [put-lock retrieve-lock internal-store]
  protocols/ImageStore
  (check-health! [_]
    true)
  (put-image! [_ data]
    (acquire! put-lock)
    (try
      (protocols/put-image! internal-store data)
      (finally
        (release! put-lock))))
  (retrieve-image! [_ id]
    (acquire! retrieve-lock)
    (try
      (protocols/retrieve-image! internal-store id)
      (finally
        (release! retrieve-lock)))))

(defn make-locking-image-store
  [put-lock retrieve-lock]
  (let [internal-store (dummy-image-store)]
    (->LockingImageStore put-lock retrieve-lock internal-store)))
