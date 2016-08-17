(ns aufi.system.deep-merge
  (:require [clojure.string :as string]))

(defn- merge-with-path
  "Variant of `merge-with` that keeps track of the path into the map."
  [f path maps]
  (reduce
    (fn [m m']
      (reduce
        (fn [m [k v]]
          (if (contains? m k)
            (assoc m k (f (conj path k) [(get m k) v]))
            (assoc m k v)))
        m (or m' {})))
    {} maps))

(defn- throw-mismatch
  "Throw exception if map and non-map values are encountered (instead
   of replacing the existing one)."
  [path vs]
  (throw
    (ex-info
      (str "merging map and non-map values at "
           (pr-str (vec path)) ": "
           (string/join ", " (map pr-str vs)))
      {:path path, :vs vs})))

(defn- deep-merge*
  "Perform deep merge while keeping track of the path into
   the map (for error reporting)."
  [path maps]
  (cond (every? #(or (nil? %) (map? %)) maps)
        (merge-with-path deep-merge* path maps)
        (some map? maps) (throw-mismatch path maps)
        :else (last maps)))

(defn deep-merge
  "Perform deep merge. This will throw an exception if during the
   merge process map and non-map values would be merged."
  [& maps]
  (deep-merge* [] maps))
