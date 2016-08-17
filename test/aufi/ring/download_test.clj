(ns aufi.ring.download-test
  (:require [aufi.ring.download :refer [get! schema]]
            [aufi.system.protocols :as p]
            [aufi.images.io :as image]
            [aufi.test :as test]
            [clojure.java.io :as io]
            [clojure.test :refer :all]
            [ronda.routing.bidi :as bidi]
            [ronda.schema :refer [wrap-schema]]))

;; ## Fixture

(defn setup
  [& [content-type]]
  (let [image        (test/generate-image-bytes
                       {:width  100
                        :height 50})
        length       (alength image)
        store        (test/dummy-image-store)
        log          (atom [])
        resizer      (test/dummy-resizer log)
        content-type (or content-type "image/jpeg")
        image-id     (p/put-image!
                       store
                       {:stream image
                        :length length
                        :metadata {:content-type content-type}})
        desc         (bidi/descriptor [["/down/" :id] :download])
        make-handler (fn [opts]
                       (wrap-schema
                         #(get!
                            {:image-store store
                             :image-resizer resizer
                             :config {:ring {:download opts}}}
                            %)
                         schema))
        download     (fn [params & [opts]]
                       (some-> ((make-handler opts)
                                {:request-method :get
                                 :ronda/descriptor desc
                                 :params (merge {:id image-id} params)})
                               (update :body #(some-> % test/as-bytes))))]
    {:image    image
     :length   length
     :log      log
     :download download}))

;; ## Tests

(deftest t-download
  (let [{:keys [image length log download]} (setup)]
    (testing "image download + resize."
      (are [params e] (let [{:keys [status headers body]} (download params)]
                        (is (= 200 status))
                        (is (= "image/jpeg" (headers "content-type")))
                        (is (= (alength body) (headers "content-length")))
                        (is (= length (alength body)))
                        (is (= e (last @log)))
                        (reset! log []))
           {:width 50}                     [:resize :default 50 nil]
           {:height 50}                    [:resize :default nil 50]
           {:max-width 50}                 [:resize :default 50 nil]
           {:max-height 50}                [:resize :none nil 50]
           {:max-width 50, :max-height 50} [:resize :default 50 50]
           {:width 50, :height 50}         [:resize :crop 50 50]
           {:max-width 100 :max-height 50} [:resize :none 100 50]
           {:max-width 200 :max-height 90} [:resize :none 200 90]
           {}                              nil))
    (testing "parameter combination not allowed."
      (are [params] (let [{:keys [status body]} (download params)]
                      (is (= 422 status))
                      (is (empty? @log))
                      (is (re-find #":request-constraint-failed" body))
                      (reset! log []))
           {:max-height 100, :width 100}
           {:max-height 100, :height 100}
           {:max-width 100, :width 100}
           {:max-width 100, :height 100}
           {:max-height 100, :max-width 100, :width 100}
           {:max-height 100, :max-width 100, :height 100}))
    (testing "dimensions exceeeded."
      (are [params opts] (let [{:keys [status body]} (download params opts)]
                           (is (= 422 status))
                           (is (empty? @log))
                           (is (re-find
                                 #"maximum dimensions \(\d+x\d+\) exceeded\."
                                 body))
                           (reset! log []))
           {:height 101} {:max-height 100}
           {:width 101} {:max-width 100}))
    (testing "max-dimensions exceeding original size."
      (are [params accessor expected] (let [{:keys [status body]} (download params)]
                                        (is (= 200 status))
                                        (is (= expected (->> body
                                                             io/input-stream
                                                             image/read-image
                                                             accessor))))
        {:max-height 200} .getHeight 50
        {:max-width 400} .getWidth 100))
    (testing "default if not found"
      (let [default-id (str (gensym))
            {:keys [headers status]} (download {:id (str (gensym))
                                                :default default-id})]
        (is (= 302 status))
        (is (= (str "/down/" default-id)
               (headers "location"))))
      (testing "preserving params"
        (let [{:keys [headers]} (download {:id (str (gensym))
                                           :width 34
                                           :height 12
                                           :default (str (gensym))})
              location (headers "location")]
          (is (re-find #"width=34" location))
          (is (re-find #"height=12" location)))))
    (testing "unknown image."
      (let [{:keys [status body]} (download {:id (str (gensym))})]
        (is (= 404 status))
        (is (re-matches #"no such object: .*" body ))))))

(deftest t-download-normalise
  (testing "MIME normalisation."
    (let [{:keys [download]} (setup "image/pjpeg")
          {:keys [status headers]} (download {})]
      (is (= (headers "content-type") "image/jpeg")))))
