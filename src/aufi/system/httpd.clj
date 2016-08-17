(ns aufi.system.httpd
  (:require [peripheral.core :refer [defcomponent]]
            [clojure.tools.logging :refer [infof]]
            [aleph.http :as aleph]))

;; ## Options

(def ^:private default-opts
  {:bind "0"})

(defn- read-opts
  [{:keys [httpd]}]
  (merge default-opts httpd))

;; ## Start/Stop

(defn- start-server!
  [handler opts]
  (aleph/start-server handler opts))

(defn- stop-server!
  [^java.io.Closeable server]
  (.close server))

(defn- print-startup-message!
  [{:keys [bind port]}]
  (infof "server is running on %s:%d ..." bind port))

;; ## Component

(defcomponent HTTPD [config s3 handler-fn]
  :this/as           *this*
  :component/handler (handler-fn *this*)
  :opts              (read-opts config)

  :keep-alive-promise
  (promise)
  #(deliver % true)

  :srv
  (start-server! handler opts)
  stop-server!

  :keep-alive-thread
  (doto (Thread. #(deref keep-alive-promise))
    (.start))

  :on/started (print-startup-message! opts))
