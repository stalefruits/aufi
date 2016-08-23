(ns aufi.core-test
  (:require [clojure.test :refer :all]
            [aufi.core :refer [make-aufi]]
            [aufi.system.protocols :as protocols]
            [aufi.test :as test]
            [clj-http.client :as http]
            [peripheral.core :refer [with-start]]))

;; ## Data

(def ^:private host "localhost")
(def ^:private port 42654)
(def ^:private threads 4)
(def ^:private queue-length 0)
(def ^:private max-requests (+ threads queue-length))
(def ^:private max-connections (* 2 max-requests))

(def ^:private pool-opts
  {:download {:threads threads, :queue queue-length}
   :upload   {:threads threads, :queue queue-length}})

(def ^:private httpd-opts
  {:port  port
   :host  host
   :pools pool-opts})

(defn- url
  [path & [query-string]]
  (str "http://" host ":" port
       path
       (if query-string
         (str "?" query-string))))

(defn- close!
  [response]
  (update response :body #(some-> % (.close))))

(defn- post!
  [path body]
  (try
    (http/post (url path) {:body body, :as :stream})
    (catch clojure.lang.ExceptionInfo ex
      (ex-data ex))))

(defn- get!
  [path & [query-string]]
  (try
    (http/get (url path query-string)
              {:follow-redirects false
               :as               :stream
               :throw-exceptions false})
    (catch clojure.lang.ExceptionInfo ex
      (ex-data ex))))

(defn- head!
  [path & [etag]]
  (try
    (http/request
       {:method :head
        :url (url path nil)
        :headers (if etag {"if-none-match" etag} {})
        :follow-redirects false})
    (catch clojure.lang.ExceptionInfo ex
      (ex-data ex))))

(def get-info!
  (comp close! get!))

(defn- test-get!
  []
  (close! (get! "/v1/images/unknown")))

(let [image (test/generate-image-bytes)]
  (defn- test-post!
    []
    (close! (post! "/v1/images" image))))

;; ## Fixtures

(defn- test-system
  [& [health-check-fn]]
  (make-aufi
    {:image-store (test/dummy-image-store health-check-fn)
     :config {:httpd httpd-opts}}))

(defn- locking-test-system
  [& [put-lock retrieve-lock]]
  (make-aufi
    {:image-store (test/make-locking-image-store
                    (or put-lock (test/lock))
                    (or retrieve-lock (test/lock)))
     :config {:httpd httpd-opts}}))

;; ## Tests

(deftest t-integration
  (with-start [aufi (test-system)]
    (testing "POST, then GET"
      (let [image (test/generate-image-bytes
                    {:format "image/jpeg"
                     :width  100
                     :height 50})
            {:keys [status headers]} (post! "/v1/images" image)
            location (headers "location")]
        (testing "- POST."
          (is (= 201 status)))
        (testing "- GET original."
          (let [{:keys [status body]} (get! location)
                data (test/as-bytes body)]
            (is (= 200 status))
            (is (= (seq data) (seq image)))
            (is (= [100 50] (test/dimensions data)))))
        (testing "- GET original with filename."
          (let [{:keys [status body]} (get! (str location "/test.jpg"))
                data (test/as-bytes body)]
            (is (= 200 status))
            (is (= (seq data) (seq image)))
            (is (= [100 50] (test/dimensions data)))))
        (testing "- GET scaled by width."
          (let [{:keys [status headers body]} (get! location "width=50")]
            (is (= 200 status))
            (is (= "image/jpeg" (headers "content-type")))
            (is (= [50 25] (test/dimensions body)))))
        (testing "- GET scaled by height."
          (let [{:keys [status body]} (get! location "height=25")]
            (is (= 200 status))
            (is (= [50 25] (test/dimensions body)))))
        (testing "- GET bounded"
          (let [{:keys [status body]} (get!
                                        location
                                        "max-width=20&max-height=25")]
            (is (= 200 status))
            (is (= [20 10] (test/dimensions body)))))
        (testing "- GET cropped."
          (let [{:keys [status body]} (get! location "width=25&height=25")]
            (is (= 200 status))
            (is (= [25 25] (test/dimensions body)))))
        (let [{:keys [body headers]} (get! location)
              expected-content-length (some-> body
                                              (test/as-bytes)
                                              (alength)
                                              (str))
              etag (headers "etag")]
          (testing "- HEAD."
            (let [{:keys [status headers body]} (head! location)]
              (is (= 200 status))
              (is (= "" (str (some-> body slurp))))
              (is (= expected-content-length (headers "content-length")))))
          (testing "- HEAD (w/ ETag)."
            (let [{:keys [status headers body]} (head! location etag)]
              (is (= 304 status))
              (is (= "" (str (some-> body slurp))))
              (is (= "0" (headers "content-length"))))))))
    (testing "GET unknown ID."
      (let [{:keys [status]} (get-info! "/v1/images/unknown")]
        (is (= 404 status)))
      (testing "with a default ID"
        (let [{:keys [status headers]} (get-info! "/v1/images/unknown"
                                                  "default=bar")]
          (is (= 302 status))
          (is (re-find #".*/bar$" (headers "location")))))
      (testing "with a default ID + filename"
        (let [{:keys [status headers]} (get-info! "/v1/images/unknown/test.jpg"
                                                  "default=bar")]
          (is (= 302 status))
          (is (re-find #".*/bar/test\.jpg$" (headers "location"))))))))

(deftest t-health-check
  (testing "successful health check."
    (with-start [aufi (test-system)]
      (let [{:keys [status]} (get-info! "/_status")]
        (is (= 200 status)))))
  (testing "health check timeout."
    (with-start [aufi (test-system #(Thread/sleep 200))]
      (let [{:keys [status]} (get-info! "/_status?timeout=100")]
        (is (= 504 status)))))
  (testing "failing health check."
    (with-start [aufi (test-system #(throw (Exception.)))]
      (let [{:keys [status]} (get-info! "/_status")]
        (is (= 503 status))))))

;; ## Saturation Tests

(defn- bombard!
  [n statuses request-fn]
  (doall
    (for [_ (range n)]
      (future
        (->> (request-fn)
             (:status)
             (swap! statuses conj))))))

(defn- wait-for-congestion!
  [lock]
  (is (test/wait-for-congestion! lock 2)
      "failed to use up all server threads within 2s."))

(defn- wait!
  [lock bombardment]
  (test/release! lock)
  (doseq [fut bombardment]
    @fut)
  true)

(defn- test-saturation!
  [saturate-fn   saturate-success-status
   unsaturate-fn unsaturate-success-status
   lock]
  (let [bombard-count (dec max-connections)
        expected-failures (- bombard-count max-requests)
        statuses (atom [])
        bombardment (bombard! bombard-count statuses saturate-fn)]
    (when (wait-for-congestion! lock)
      (is (= unsaturate-success-status (:status (unsaturate-fn))))
      (is (wait! lock bombardment))
      (let [fq (frequencies @statuses)]
        (is (= max-requests (get fq saturate-success-status)))
        (is (= expected-failures (get fq 503)))))))

(deftest t-saturation
  (testing "POST saturation does not influence GET requests."
    (let [lock (test/lock! threads)]
      (with-start [aufi (locking-test-system lock nil)]
        (test-saturation!
          test-post! 201
          test-get!  404
          lock))))
  (testing "GET saturation does not influence POST requests."
    (let [lock (test/lock! threads)]
      (with-start [aufi (locking-test-system nil lock)]
        (test-saturation!
          test-get!  404
          test-post! 201
          lock)))))

;; ## Health during Saturation

(deftest t-saturation-health
  (let [check! #(->> (str "download-capacity=" %
                          "&upload-capacity=" %)
                     (get-info! "/_status")
                     :status)
        put-lock (test/lock! threads)
        retrieve-lock (test/lock! threads)]
    (testing "health check based on thresholds."
      (with-start [aufi (locking-test-system put-lock retrieve-lock)]
        (is (= 200 (check! 0.5)))
        (testing "GET saturation."
          (let [bombardment (bombard!  max-requests (atom []) test-get!)]
            (when (wait-for-congestion! retrieve-lock)
              (is (= 503 (check! 0.5)))
              (is (= 200 (check! 0.0)))
              (wait! retrieve-lock bombardment)
              (is (= 200 (check! 0.5))))))
        (testing "POST saturation."
          (let [bombardment (bombard!  max-requests (atom []) test-post!)]
            (when (wait-for-congestion! put-lock)
              (is (= 503 (check! 0.5)))
              (is (= 200 (check! 0.0)))
              (wait! put-lock bombardment)
              (is (= 200 (check! 0.5))))))))))
