(ns aufi.core-test
  (:require [clojure.test :refer :all]
            [aufi.core :refer [make-aufi]]
            [aufi.system.protocols :as protocols]
            [aufi.test :as test]
            [aleph.http :as http]
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

(defonce connection-pool
  (http/connection-pool
    {:connections-per-host max-connections}))

(defn- url
  [path & [query-string]]
  (str "http://" host ":" port
       path
       (if query-string
         (str "?" query-string))))

(defn- post!
  [path body]
  (try
    @(http/post (url path) {:body body, :pool connection-pool})
    (catch clojure.lang.ExceptionInfo ex
      (ex-data ex))))

(defn- get!
  [path & [query-string]]
  (try
    @(http/get (url path query-string)
               {:follow-redirects false
                :pool connection-pool})
    (catch clojure.lang.ExceptionInfo ex
      (ex-data ex))))

(defn- head!
  [path & [etag]]
  (try
    @(http/request
       {:method :head
        :url (url path nil)
        :headers (if etag {"if-none-match" etag} {})
        :follow-redirects false
        :pool connection-pool})
    (catch clojure.lang.ExceptionInfo ex
      (ex-data ex))))

(defn- test-get!
  []
  (get! "/v1/images/unknown"))

(let [image (test/generate-image-bytes)]
  (defn- test-post!
    []
    (post! "/v1/images" image)))

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
                    (or put-lock (test/make-lock))
                    (or retrieve-lock (test/make-lock)))
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
              (is (= "" (slurp body)))
              (is (= expected-content-length (headers "content-length")))))
          (testing "- HEAD (w/ ETag)."
            (let [{:keys [status headers body]} (head! location etag)]
              (is (= 304 status))
              (is (= "" (slurp body)))
              (is (= "0" (headers "content-length"))))))))
    (testing "GET unknown ID."
      (let [{:keys [status]} (get! "/v1/images/unknown")]
        (is (= 404 status)))
      (testing "with a default ID"
        (let [{:keys [status headers]} (get! "/v1/images/unknown"
                                             "default=bar")]
          (is (= 302 status))
          (is (re-find #".*/bar$" (headers "location")))))
      (testing "with a default ID + filename"
        (let [{:keys [status headers]} (get! "/v1/images/unknown/test.jpg"
                                             "default=bar")]
          (is (= 302 status))
          (is (re-find #".*/bar/test\.jpg$" (headers "location"))))))))

(deftest t-health-check
  (testing "successful health check."
    (with-start [aufi (test-system)]
      (let [{:keys [status body]} (get! "/_status")]
        (is (= 200 status)))))
  (testing "health check timeout."
    (with-start [aufi (test-system #(Thread/sleep 200))]
      (let [{:keys [status body]} (get! "/_status?timeout=100")]
        (is (= 504 status)))))
  (testing "failing health check."
    (with-start [aufi (test-system #(throw (Exception.)))]
      (let [{:keys [status body]} (get! "/_status")]
        (is (= 503 status))))))

;; ## Saturation Tests

(defn- lock!
  []
  (doto (test/make-lock)
    (.lock)))

(defn- bombard!
  [n statuses request-fn]
  (doall
    (for [_ (range n)]
      (future
        (->> (request-fn)
             (:status)
             (swap! statuses conj))))))

(defn- unlock!
  [lock bombardment]
  (.unlock lock)
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
    (Thread/sleep 100)
    (is (= unsaturate-success-status (:status (unsaturate-fn))))
    (is (unlock! lock bombardment))
    (let [fq (frequencies @statuses)]
      (is (= max-requests (get fq saturate-success-status)))
      (is (= expected-failures (get fq 503))))))

(deftest t-saturation
  (testing "POST saturation does not influence GET requests."
    (let [lock (lock!)]
      (with-start [aufi (locking-test-system lock)]
        (test-saturation!
          test-post! 201
          test-get!  404
          lock))))
  (testing "GET saturation does not influence POST requests."
    (let [lock (lock!)]
      (with-start [aufi (locking-test-system nil lock)]
        (test-saturation!
          test-get!  404
          test-post! 201
          lock)))))

;; ## Health during Saturation

(deftest t-saturation-health
  (let [check! #(->> (str "download-capacity=" %
                          "&upload-capacity=" %)
                     (get! "/_status")
                     :status)
        put-lock (lock!)
        retrieve-lock (lock!)]
    (testing "health check based on thresholds."
      (with-start [aufi (locking-test-system put-lock retrieve-lock)]
        (is (= 200 (check! 0.5)))
        (testing "GET saturation."
          (let [bombardment (bombard!  max-requests (atom []) test-get!)]
            (Thread/sleep 100)
            (is (= 503 (check! 0.5)))
            (is (= 200 (check! 0.0)))
            (unlock! retrieve-lock bombardment)
            (is (= 200 (check! 0.5)))))
        (testing "POST saturation."
          (let [bombardment (bombard!  max-requests (atom []) test-post!)]
            (Thread/sleep 100)
            (is (= 503 (check! 0.5)))
            (is (= 200 (check! 0.0)))
            (unlock! put-lock bombardment)
            (is (= 200 (check! 0.5)))))))))
