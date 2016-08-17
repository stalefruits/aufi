(ns aufi.ring.middlewares.etag)

(defn- match-etag-prefix?
  [{:keys [headers]} prefix]
  (some->> (get headers "if-none-match")
           (re-matches prefix)))

(defn- make-etag
  [prefix]
  (str prefix "-" (System/currentTimeMillis)))

(defn- attach-etag
  [{:keys [status] :as response} prefix]
  (if (<= 200 status 204)
    (let [etag (make-etag prefix)]
      (assoc-in response [:headers "etag"] etag))
    response))

(defn wrap-etag
  "Wrap the given handler to attach/validate an ETag solely depending on URI,
   query string and a user-defined prefix. This means without changing the
   prefix an image will not be regenerated even after the cache has expired."
  [handler {:keys [prefix] :or {prefix "aufi"}}]
  (let [prefix-pattern (re-pattern (str prefix "-.*"))]
    (fn [{:keys [request-method headers] :as request}]
      (if (= request-method :get)
        (if (match-etag-prefix? request prefix-pattern)
          {:status 304}
          (some-> request
                  (handler)
                  (attach-etag prefix)))
        (handler request)))))
