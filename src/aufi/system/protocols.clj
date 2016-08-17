(ns aufi.system.protocols
  (:require [potemkin :refer [defprotocol+]]))

(defprotocol+ ImageStore
  "Protocol for an image store."
  (check-health! [this]
    "Check whether the image store is up and running. Should either throw
     or return a `Throwable`.")
  (put-image! [this image-data]
    "Expects a map of `:stream` (an `InputStream` with the image data),
     `:length` (the data length) and `:metadata`, stores the given data
     and returns an ID representing it.")
  (retrieve-image! [this image-id]
    "Retrieve a map of `:stream` (an `InputStream` with the image data),
     `:length` and `:metadata`."))

(defprotocol+ ImageResizer
  "Protocol for an image resizing component."
  (resize-image! [this buffered-image width height resizing-type]
    "Resize the given image to the given width/height, using the given resizing
     type:

     - `:default`: maintain aspect ratio, resulting in an image potentially
       smaller than the desired dimensions,
     - `:crop`: create exactly the desired dimensions, potentially cropping the
       image in one of them."))
