(ns aufi.core
  (:require [aufi
             [ring :as ring]
             [system :as system]]
            [peripheral.core :as peripheral])
  (:gen-class))

(defn make-aufi
  [& [overrides]]
  (system/generate
    ring/generate-handler
    overrides))

(defonce system nil)

(defn stop
  []
  (alter-var-root #'system #(some-> % peripheral/stop)))

(defn start
  [config-path]
  (stop)
  (->> (when config-path
         {:config (system/file-config config-path)})
       (make-aufi)
       (peripheral/start)
       (constantly)
       (alter-var-root #'system)))

(defn -main
  [& [config-path]]
  (start config-path)
  :ok)
