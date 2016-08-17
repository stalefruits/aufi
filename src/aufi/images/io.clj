(ns aufi.images.io
  (:require [clojure.java.io :as io])
  (:import [javax.imageio ImageIO IIOImage ImageWriter ImageWriteParam]
           [javax.imageio.stream ImageOutputStream]
           [java.awt Image Color Graphics2D RenderingHints]
           [java.awt.image BufferedImage]
           [java.io ByteArrayOutputStream]))

;; ## ImageWriter

(defn- image-writer
  ^javax.imageio.ImageWriter
  [^String image-format]
  (.next ^java.util.Iterator (ImageIO/getImageWritersByFormatName image-format)))

(defn- image-params
  [^ImageWriter writer {:keys [quality]}]
  (when-let [^ImageWriteParam param (.getDefaultWriteParam writer)]
    (when (and quality (.canWriteCompressed param))
      (doto param
        (.setCompressionMode ImageWriteParam/MODE_EXPLICIT)
        (.setCompressionQuality quality)))
    param))

(defn- write-image-bytes
  [^ImageWriter writer ^BufferedImage image & [{:keys [quality] :as options}]]
  (let [param (image-params writer options)]
    (with-open [bs (ByteArrayOutputStream.)]
      (with-open [is (ImageIO/createImageOutputStream bs)]
        (doto writer
          (.setOutput is)
          (.write nil (IIOImage. image nil nil) param)))
      (.toByteArray bs))))

;; ## MIME Utilities

(def ^:private mime-type->opts
  (let [defaults {"image/jpeg" {:quality 0.95}}]
    (fn [mime-type opts]
      (merge (defaults mime-type) opts))))

(defn- mime-type->writer
  ^javax.imageio.ImageWriter
  [^String mime-type]
  {:pre [(.startsWith mime-type "image/")]}
  (image-writer (subs mime-type 6)))

;; ## Output

(defn image->bytes
  "Create byte array representing the given BufferedImage using the
   given MIME type."
  [buffered-image mime-type & {:as opts}]
  (let [writer (mime-type->writer mime-type)
        opts (mime-type->opts mime-type opts)]
    (try
      (write-image-bytes writer buffered-image opts)
      (finally
        (.dispose writer)))))

;; ## Reader

(defn read-image
  "Read image from everything that can be coerced to an InputStream."
  ^java.awt.image.BufferedImage
  [image]
  (with-open [in (io/input-stream image)]
    (ImageIO/read in)))

(defn flush-image
  [^BufferedImage image]
  (.flush image))
