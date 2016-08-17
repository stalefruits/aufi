(ns aufi.ring.upload-test
  (:require [clojure.test :refer :all]
            [aufi.test :as test]
            [aufi.ring
             [upload :refer [post! schema]]
             [mime-types :as mime]]
            [aufi.system.protocols :as p]
            [ronda.routing.bidi :as bidi]
            [ronda.schema :refer [wrap-schema]]))

;; ## Fixture

(defn setup
  [mime-type]
  (let [image        (test/generate-image-bytes {:format mime-type})
        store        (test/dummy-image-store)
        desc         (bidi/descriptor [["/down/" :id :filename] :download])
        make-handler (fn [opts]
                       (wrap-schema
                         #(post!
                            {:image-store store
                             :config {:ring {:upload opts}}}
                            %)
                         schema))
        upload       (fn [body & [opts]]
                       ((make-handler opts)
                        {:request-method :post
                         :ronda/descriptor desc
                         :uri   "/"
                         :body (test/as-stream body)}))]
    {:image  image
     :store  store
     :upload upload}))

;; ## Tests

(deftest t-upload
  (doseq [mime-type mime/mime-types]
    (testing mime-type
      (let [{:keys [image store upload]} (setup mime-type)]
        (testing "successful upload."
          (let [{:keys [status headers body]} (upload image)
                loc (headers "location")]
            (is (= 201 status))
            (is (re-matches #"/down/.*" loc))
            (let [id (subs loc 6)
                  {:keys [stream length metadata]} (p/retrieve-image! store id)]
              (is (= (alength image) length))
              (is (= (:content-type metadata) mime-type))
              (is (= (seq image) (seq (test/as-bytes stream)))))))
        (testing "rejected MIME type."
          (let [{:keys [status body]} (upload (.getBytes "hello"))]
            (is (= 415 status))
            (is (re-matches #"MIME type '.*' is not allowed\." body))))
        (testing "max data length exceeded."
          (let [{:keys [status body]} (upload
                                        (byte-array 101)
                                        {:max-length 100})]
            (is (= 413 status))
            (is (= body "data is too long (max: 100 bytes)."))))))))
