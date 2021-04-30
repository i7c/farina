(ns farina.grafana-querier
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json]
            [farina.awsclient :as client]
            [cognitect.aws.client.api :as aws])
  (:import [com.amazonaws.services.lambda.runtime RequestStreamHandler])
  (:gen-class
    :name farina.GrafanaHandler
    :implements [com.amazonaws.services.lambda.runtime.RequestStreamHandler]))

(defn query-for-space [space]
  (aws/invoke @client/dynamo
              {:op :Query
               :request {:TableName "farina-germany"
                         :KeyConditionExpression "#spce = :s"
                         :ExpressionAttributeValues {":s" {:S space}}
                         :ExpressionAttributeNames {"#spce" "space"}
                         :ScanIndexForward false}}))

(defn raw [request]
  (let [space (get-in request ["queryStringParameters" "space"] "120122-alder")
        rawdata (query-for-space space)]
    (map #(do {:intensity (get-in % [:intensity :N]) :date (get-in % [:date :N])})
         (:Items rawdata))))

(defn -handleRequest [_ in out context]
  (with-open [writer (io/writer out)
              reader (io/reader in)]
    (let [request (json/read-str (slurp reader))
          path (get-in request ["requestContext" "http" "path"])
          result (cond
                   (clojure.string/starts-with? path "/raw") (raw request)
                   (clojure.string/starts-with? path "/annotations") (list)
                   (clojure.string/starts-with? path "/search") (list "pollen")
                   (clojure.string/starts-with? path "/") "OK"
                   :else (list))]
      (.write writer (json/write-str result)))))
