(ns aufi.ring.middlewares.head)

(defn- clear-body
  [{:keys [body] :as response}]
  (when (instance? java.io.Closeable body)
    (.close ^java.io.Closeable body))
  (dissoc response :body))

(defn wrap-head
  "Wrap the given handler with HEAD processing. HEAD requests should
   not return a body, therefore this middleware eliminates any body
   from responses."
  [handler]
  (fn [{:keys [request-method] :as request}]
    (if (= :head request-method)
      (some-> request
              (assoc :request-method :get)
              (handler)
              (clear-body))
      (handler request))))
