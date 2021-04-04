(ns farina.grafana-querier
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json])
  (:import [com.amazonaws.services.lambda.runtime RequestStreamHandler])
  (:gen-class
    :name farina.GrafanaHandler
    :implements [com.amazonaws.services.lambda.runtime.RequestStreamHandler]))

(defn -handleRequest [_ in out context]
  (with-open [writer (io/writer out)
              reader (io/reader in)
              request (json/read-str (slurp reader))]
    (.write writer (str request))))
