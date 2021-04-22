(ns farina.grafana-querier
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json]
            [farina.awsclient :as client]
            [cognitect.aws.client.api :as aws])
  (:import [com.amazonaws.services.lambda.runtime RequestStreamHandler])
  (:gen-class
    :name farina.GrafanaHandler
    :implements [com.amazonaws.services.lambda.runtime.RequestStreamHandler]))

(defn -handleRequest [_ in out context]
  (with-open [writer (io/writer out)
              reader (io/reader in)]
    (let [request (json/read-str (slurp reader))
          space (get-in request ["queryStringParameters" "space"] "120122-alder")
          rawdata (aws/invoke @client/dynamo
                              {:op :Query
                               :request {:TableName "farina-germany"
                                         :KeyConditionExpression "#spce = :s"
                                         :ExpressionAttributeValues {":s" {:S space}}
                                         :ExpressionAttributeNames {"#spce" "space"}
                                         :ScanIndexForward false}})
          data (map #(do {:intensity (get-in % [:intensity :N])
                          :date (get-in % [:date :N])})
                    (:Items rawdata))]
      (.write writer (json/write-str data)))))
