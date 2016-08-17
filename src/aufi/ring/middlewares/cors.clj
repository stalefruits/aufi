(ns aufi.ring.middlewares.cors)

(def ^:private options-headers
  {"access-control-allow-origin"      "*"
   "access-control-allow-methods"     "GET, POST, DELETE, PUT, PATCH, OPTIONS"
   "access-control-allow-credentials" "true"
   "access-control-allow-headers"     "Content-Type"
   "access-control-expose-headers"    "Location"
   "cache-control"                    "public, max-age=300"})

(def ^:private headers
  (select-keys
    options-headers
    ["access-control-allow-origin"
     "access-control-allow-credentials"
     "access-control-expose-headers"]))

(defn- set-origin
  [request response]
  (if-let [origin (get-in request [:headers "origin"])]
    (assoc-in response [:headers "access-control-allow-origin"] origin)
    response))

(defn wrap-cors
  "Wrap handler to attach 'Access-Control-Allow-Origin' to all
   outgoing responses."
  [handler]
  (fn [{:keys [request-method] :as request}]
    (some->> (if (= request-method :options)
               {:status 204
                :headers options-headers}
               (some-> (handler request)
                       (update-in [:headers] merge headers)))
             (set-origin request))))
