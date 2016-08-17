(ns aufi.images.op
  (:require [aufi.images.io :refer [flush-image]])
  (:import [org.imgscalr Scalr Scalr$Method Scalr$Mode]
           [javax.imageio ImageIO]
           [java.awt.image BufferedImage BufferedImageOp]))

;; ## Helpers

(def ^:private no-ops
  (into-array BufferedImageOp []))

(defn- dimensions
  [^BufferedImage image]
  (vector
    (.getWidth image)
    (.getHeight image)))

(defn- scales
  [^BufferedImage image width height]
  (vector
    (/ width (.getWidth image))
    (/ height (.getHeight image))))

(defn- scale-mode
  [width?]
  (if width?
    Scalr$Mode/FIT_TO_WIDTH
    Scalr$Mode/FIT_TO_HEIGHT))

(def ^:private scale-method
  Scalr$Method/QUALITY)

;; ## Scales

(defn scale
  ([^BufferedImage image mode size]
   (Scalr/resize image scale-method mode size no-ops))
  ([^BufferedImage image mode width height]
   (Scalr/resize image scale-method mode width height no-ops)))

(defn scale-to
  [^BufferedImage image width height]
  (if (and width height)
    (let [[scale-h scale-v] (scales image width height)
          mode (scale-mode (< scale-h scale-v))]
      (scale image mode width height))
    (let [mode (scale-mode (some? width))]
      (scale image mode (or width height)))))

(defn crop
  [^BufferedImage image width height]
  (let [[w h] (dimensions image)
        x (quot (- w width) 2)
        y (quot (- h height) 2)]
    (Scalr/crop image x y width height no-ops)))

(defn scale-crop
  [^BufferedImage image width height]
  (let [[scale-h scale-v] (scales image width height)
        mode (scale-mode (< scale-v scale-h))
        scaled (scale image mode width height)
        result (crop scaled width height)]
    (flush-image scaled)
    result))
