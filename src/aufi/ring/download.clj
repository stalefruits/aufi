(ns aufi.ring.download
  (:require [aufi.system.protocols :as p]
            [aufi.ring.mime-types :as mime]
            [ronda.routing :as routing]
            [aufi.images.io :as image]
            [schema.core :as s]
            [clojure.java.io :as io])
  (:import [java.io ByteArrayOutputStream]))

;; ## Resize

(defmulti ^:private resized-image
  (fn [system image-data {:keys [max-width max-height width height]}]
    (cond (or max-width max-height)  :bounded
          (and width height)         :fixed
          (or width height)          :scaled
          :else                      :identity)))

(defmethod resized-image :identity
  [_ {:keys [stream metadata length]} _]
  {:data    stream
   :length  length
   :type    (-> metadata :content-type mime/normalise-mime-type)})

(defn- resized-data
  [image {:keys [content-type]}]
  (let [content-type (mime/normalise-mime-type content-type)
        image-bytes (image/image->bytes image content-type)]
    {:data   image-bytes
     :length (alength image-bytes)
     :type   content-type}))

(defn- resize-buffered-image-as!
  [image-resizer resizing-type buffered-image metadata {:keys [width height]}]
  (let [result (resized-data
                 (p/resize-image!
                   image-resizer
                   buffered-image
                   width
                   height
                   resizing-type)
                 metadata)]
    (image/flush-image buffered-image)
    result))

(defn- resize-as!
  [image-resizer resizing-type {:keys [stream metadata]} params]
  (resize-buffered-image-as!
    image-resizer
    resizing-type
    (image/read-image stream)
    metadata
    params))

(defmethod resized-image :fixed
  [{:keys [image-resizer]} image params]
  (resize-as! image-resizer :crop image params))

(defmethod resized-image :bounded
  [{:keys [image-resizer]} {:keys [stream metadata]} {:keys [max-width max-height]}]
  (with-open [stream stream]
    (let [image (image/read-image stream)
          height (.getHeight image)
          width (.getWidth image)]
      (resize-buffered-image-as!
        image-resizer
        (if (or (some-> max-height (< height))
                (some-> max-width (< width)))
          :default
          :none)
        image
        metadata
        {:width max-width :height max-height}))))

(defmethod resized-image :scaled
  [{:keys [image-resizer]} image params]
  (resize-as! image-resizer :default image params))

;; ## Schema

(def positive-int
  (s/both
    s/Int
    (s/pred pos? 'positive?)))

(defn- combination-allowed?
  "If max-width or max-height is given, neither width nor height are allowed."
  [{:keys [params]}]
  (let [{:keys [max-width max-height width height]} params]
    (or (not (or max-width max-height))
        (not (or width height)))))

(def schema
  {:get {:params    {:id s/Str
                     (s/optional-key :default)    s/Str
                     (s/optional-key :width)      positive-int
                     (s/optional-key :height)     positive-int
                     (s/optional-key :max-width)  positive-int
                     (s/optional-key :max-height) positive-int}
         :constraint (s/pred combination-allowed? 'combination-allowed?)
         :responses {200 {:headers {"content-type"   mime/mime-types-schema
                                    "content-length" s/Int}
                          :body    s/Any}
                     302 {:headers {"location" s/Str}}
                     404 {}
                     422 {}}}})

;; ## Handler

(defn- body-for
  [{:keys [data]}]
  data)

(defn- headers-for
  [{:keys [length type]}]
  {"content-type"   type
   "content-length" length})

(def ^:private dimensions-of
  (juxt :width :height))

(defn- maybe-reject-dimensions
  [params {:keys [max-width max-height]
           :or {max-width  1280
                max-height 1280}}]
  (let [[width height] (dimensions-of params)]
    (when (or (some-> width (> max-width))
              (some-> height (> max-height)))
      {:status 422
       :body (format "maximum dimensions (%dx%d) exceeded."
                     max-width
                     max-height)})))

(defn- maybe-retrieve-image
  [{:keys [image-store] :as system} {:keys [id] :as params}]
  (when-let [raw-image (p/retrieve-image! image-store id)]
    (let [image (resized-image system raw-image params)]
      {:status  200
       :body    (body-for image)
       :headers (headers-for image)})))

(defn- maybe-default-image
  [{:keys [params] :as request}]
  (when-let [default (:default params)]
    {:status  302
     :body    (str "couldn't find the requested file; redirecting to " default)
     :headers (let [new-params (-> params
                                   (dissoc :default)
                                   (assoc :id default))]
                {"location" (routing/href request :download new-params)})}))

(defn- image-not-found
  [{:keys [id]}]
  {:status 404
   :body (str "no such object: " id)})

(defn get!
  [{:keys [image-store config] :as system} {:keys [params] :as request}]
  (let [opts (-> config :ring :download)]
    (or (maybe-reject-dimensions params opts)
        (maybe-retrieve-image system params)
        (maybe-default-image request)
        (image-not-found params))))
