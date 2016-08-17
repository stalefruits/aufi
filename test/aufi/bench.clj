(ns aufi.bench
  (:require [criterium.core :as criterium]
            [aufi.system
             [protocols :as p]
             [resizer :as rs]]
            [clojure.java.io :as io])
  (:import [javax.imageio ImageIO]))

(defn resize-bench
  "Benchmark the Resizer Component."
  [path width height & [resize-mode]]
  (let [resizer (rs/->Resizer)
        image   (with-open [in (io/input-stream path)]
                  (ImageIO/read in))
        mode    (or resize-mode :default)]
    (criterium/bench
      (p/resize-image! resizer image width height mode))))
