(ns aufi.ring.mime-types-test
  (:require [clojure.test :refer :all]
            [aufi.ring.mime-types :refer :all]
            [aufi.test :as test]))

(deftest t-read-mime
  (testing "reading of image formats."
    (are [mime] (= mime (read-mime
                          (test/generate-image-bytes
                            {:format mime})))
         "image/gif"
         "image/jpeg"
         "image/png")))

(deftest t-normalise-mime-type
  (testing "MIME normalisation."
    (are [in out] (= out (normalise-mime-type in))
         "image/jpeg"  "image/jpeg"
         "image/png"   "image/png"
         "image/gif"   "image/gif"
         "image/pjpeg" "image/jpeg"
         "image/x-png" "image/png")))

(deftest t-allowed?
  (testing "allowed types."
    (are [mime-type] (allowed? mime-type)
         "image/jpeg"
         "image/png"
         "image/gif"))
  (testing "exemplary disallowed types."
    (are [mime-type] (not (allowed? mime-type))
         "image/tiff"
         "application/octetstream"
         "text/plain")))
