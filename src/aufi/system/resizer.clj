(ns aufi.system.resizer
  (:require [aufi.system.protocols :as p]
            [aufi.images.op :as op]))

(defrecord Resizer []
  p/ImageResizer
  (resize-image! [_ buffered-image width height resizing-type]
    (case resizing-type
      :default (op/scale-to buffered-image width height)
      :crop    (op/scale-crop buffered-image width height)
      :none   buffered-image)))
