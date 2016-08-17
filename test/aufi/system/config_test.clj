(ns aufi.system.config-test
  (:require [clojure.test :refer :all]
            [aufi.system.config :as config]
            [peripheral.core :refer [with-start]])
  (:import [java.io File]))

(deftest t-config
  (let [f (File/createTempFile "config" ".edn")]
    (try
      (testing "complete configuration."
        (spit f (pr-str (assoc config/defaults :s3 {:bucket "aufi"})))
        (with-start [config (config/map->Configuration {:path (.getPath f)})]
          (is (= #{:s3 :httpd :ring :path} (set (keys config))))))
      (testing "incomplete configuration."
        (spit f (pr-str config/defaults))
        (is (thrown? IllegalStateException
                     (with-start [_ (config/map->Configuration {:path (.getPath f)})]))))
      (testing "defaults."
        (spit f (pr-str {:s3 {:bucket "aufi"}}))
        (with-start [config (config/map->Configuration {:path (.getPath f)})]
          (is (= #{:s3 :httpd :ring :path} (set (keys config))))))
      (finally
        (.delete f)))))
