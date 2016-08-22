(defproject aufi "0.1.0-SNAPSHOT"
  :description "aufi is an image upload, delivery and resizing service."
  :url "https://github.com/stylefruits/aufi"
  :license {:name "Apache License 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0.html"
            :author "stylefruits GmbH"
            :year 2016
            :key "apache-2.0"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/tools.logging "0.3.1"]
                 [org.clojure/tools.reader "0.10.0"]
                 [peripheral "0.5.2"]
                 [clj-aws-s3  "0.3.10"
                  :exclusions [com.amazonaws/aws-java-sdk]]
                 [com.amazonaws/aws-java-sdk-s3 "1.11.26"
                  :exclusions [joda-time]]
                 [ronda/routing "0.2.8"]
                 [ronda/routing-bidi "0.1.3"]
                 [ronda/routing-schema "0.1.3"]
                 [org.apache.tika/tika-core "1.13"]
                 [org.imgscalr/imgscalr-lib "4.2"]
                 [com.twelvemonkeys.imageio/imageio-jpeg "3.2.1"]
                 [clj-time "0.12.0"]
                 [joda-time "2.9.4"]
                 [potemkin "0.4.3"
                  :exclusions [riddley]]
                 [aleph "0.4.1"]
                 [ch.qos.logback/logback-classic "1.1.6"]

                 ;; fix dependency conflicts
                 [commons-logging "1.2"]
                 [org.slf4j/slf4j-api "1.7.21"]
                 [com.fasterxml.jackson.core/jackson-databind "2.8.1"]]
  :pedantic? :abort
  :jvm-opts ["-Djava.awt.headless=true"]
  :profiles {:uberjar {:dependencies [[net.logstash.logback/logstash-logback-encoder "4.7"]]
                       :uberjar-name "aufi-standalone.jar"
                       :aot :all
                       :main aufi.core}
             :silent {:jvm-opts
                      ^:replace
                      ["-Djava.awt.headless=true"
                       "-Dlogback.configurationFile=resources/logback-silent.xml"]}
             :dev {:dependencies [[criterium "0.4.4"]]
                   :jvm-opts
                   ^:replace
                   ["-Djava.awt.headless=true"
                    "-Dlogback.configurationFile=resources/logback-dev.xml"]}}
  :aliases {"silent" ["with-profile" "+silent"]}
  :test-selectors {:default (complement :with-aws-credentials)
                   :all     (constantly true)})
