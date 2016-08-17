(ns aufi.ring.middlewares.rate-test
  (:require [clojure.test :refer :all]
            [aufi.test :as test]
            [aufi.ring.middlewares.rate :refer [wrap-rate]]))

(deftest t-wrap-rate
  (testing "local rate limitting."
    (let [f (constantly {:status 200})
          handler (wrap-rate f {:rate 1, :period 100})]
      (is (= {:status 200} (handler {:request-method :get})))
      (let [{:keys [status headers]} (handler {:request-method :get})]
        (is (= 429 status))
        (is (pos? (headers "retry-after"))))
      (Thread/sleep 101)
      (is (= {:status 200} (handler {:request-method :get}))))))
