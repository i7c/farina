(defproject farina "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [org.clojure/data.json "1.0.0"]
                 [com.cognitect.aws/api "0.8.505"]
                 [com.cognitect.aws/endpoints "1.1.11.960"]
                 [com.cognitect.aws/iam "811.2.844.0"]
                 [com.cognitect.aws/s3 "810.2.817.0"]
                 [clj-http "3.12.0"]]
  :main ^:skip-aot farina.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
