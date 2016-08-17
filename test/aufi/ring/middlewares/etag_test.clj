(ns aufi.ring.middlewares.etag-test
  (:require [clojure.test :refer :all]
            [aufi.test :as test]
            [aufi.ring.middlewares.etag :refer [wrap-etag]]))

(deftest t-wrap-etag
  (let [f (constantly {:status 200})
        handler (wrap-etag f {:prefix "test"})]
    (testing "ETag generation."
      (let [{:keys [status headers]} (handler
                                       {:request-method :get
                                        :uri "/"})]
        (is (= 200 status))
        (is (.startsWith (get headers "etag") "test-"))))
    (testing "ETag verification."
      (let [{:keys [status]} (handler
                               {:request-method :get
                                :uri "/"
                                :headers {"if-none-match" "test-123"}})]
        (is (= 304 status)))
      (let [{:keys [status]} (handler
                               {:request-method :get
                                :uri "/"
                                :headers {"if-none-match" "none-123"}})]
        (is (= 200 status))))
    (testing "no ETags for POST requests."
      (let [{:keys [status headers]} (handler
                                       {:request-method :post
                                        :uri "/"})]
        (is (= 200 status))
        (is (nil? (get headers "etag"))))))
  (testing "no ETags for non-20x responses."
    (let [f (constantly {:status 404})
          handler (wrap-etag f {:prefix "test"})
          {:keys [status headers]} (handler
                                     {:request-method :get
                                      :uri "/"})]
      (is (= 404 status))
      (is (nil? (get headers "etag"))))))
