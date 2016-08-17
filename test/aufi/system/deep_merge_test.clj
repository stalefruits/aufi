(ns aufi.system.deep-merge-test
  (:require [clojure.test :refer :all]
            [aufi.system.deep-merge :refer [deep-merge]]))

(deftest t-deep-merge
  (testing "normal merge."
    (is (= {:a 1, :b 2}
           (deep-merge {:a 1} {:b 2}))))
  (testing "deep merge."
    (is (= {:a {:b 1, :c 2}, :d 3}
           (deep-merge
             {:a {:b 1}, :d 2}
             {:a {:c 2}, :d 3}))))
  (testing "deep merge errors."
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"merging map and non-map values at \[:a :b\]"
          (deep-merge {:a {:b {:c 1}}} {:a {:b 0}})))))
