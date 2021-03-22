(ns farina.core
  (:require [farina.infra :as infra]
            [farina.awsclient :refer [s3 dynamo sqs]]
            [farina.dwd]
            [clojure.string :as s]
            [clojure.data.json :as json]
            [cognitect.aws.client.api :as aws]
            [clj-http.client :as client]
            [clojure.pprint :refer [pprint]])
  (:gen-class
    :methods [^:static [download [] String]
              ^:static [crunch [String] String]]))

(def basename "farina")

(defn -download []
  (let [raw (client/get
              "https://opendata.dwd.de/climate_environment/health/alerts/s31fg.json"
              {:accept :json})
        decoded (json/read-str (:body raw))
        date (re-find #"^\d{4}-\d{2}-\d{2}" (get decoded "last_update"))
        response (aws/invoke @s3 {:op :PutObject
                                  :request {:Bucket basename
                                            :Key (str "raw/" date)
                                            :Body (.getBytes (:body raw) "UTF-8")}})
        code (get-in response [:Error :Code])
        message (get-in response [:Error :Message])]
    (if (some? code) (throw (IllegalStateException. message)))
    ; write to queue that this file is ready for processing
    (let [queue-url (System/getenv "QUEUE_RAWDATA")]
      (aws/invoke @sqs {:op :SendMessage
                        :request {:QueueUrl queue-url
                                  :MessageBody date}}))
    (json/write-str response)))

(defn get-object [bucket file]
  (let [response (aws/invoke @s3 {:op :GetObject :request {:Bucket bucket
                                                           :Key file}})]
    (if (some? (:cognitect.anomalies/category response))
      (throw (IllegalStateException. (str response)))
      (slurp (:Body response)))))

(defn process-and-write-to-db [file]
  (let [raw (get-object basename file)
        json (json/read-str raw)
        extracted (farina.dwd/extract-dwd-pollen-data json)
        dynamodb-format (map #(do {:PutRequest
                                   {:Item
                                    {"date" {:N (str (:date %))}
                                     "space" {:S (str (:region %) "-" (:kind %))}
                                     "intensity" {:N (str (:intensity %))}}}})
                             extracted)]
    (let [result
          (->> dynamodb-format
               (partition-all 25)
               (map #(aws/invoke @dynamo
                                 {:op :BatchWriteItem
                                  :request {:RequestItems
                                            {"farina-germany" %}}})))
          unprocessed (map #(:UnprocessedItems %) result)]
      (if (not-every? empty? unprocessed)
        (throw (IllegalStateException. "Not all items could be processed"))
        (count dynamodb-format)))))

(defn -crunch [file]
  (let [received-messages (aws/invoke @sqs {:op :ReceiveMessage
                                            :request {:QueueUrl (System/getenv "QUEUE_RAWDATA")
                                                      :MaxNumberOfMessage 5}})]
    (if (some? (:cognitect.anomalies/category received-messages))
      (throw (IllegalStateException. (str "Could not read message from queue " received-messages))))
    (loop [messages (:Messages received-messages)
           results (list)]
      (let [process-result (process-and-write-to-db (str "raw/" (:Body (first messages))))
            all-results (conj results process-result)]
        (if (> (count messages) 0)
          (recur (rest messages) all-results)
          (do
            (aws/invoke @sqs {:op :DeleteMessageBatch
                              :request {:QueueUrl (System/getenv "QUEUE_RAWDATA")
                                        :Entries (map #(do {:Id (:MessageId %)
                                                            :ReceiptHandle (:ReceiptHandle %)})
                                                      (:Messages received-messages))}})
            all-results))))))

(defn -main [& args]
  (cond
    (and (= (count args) 2)
         (= (first args) "provision")) (infra/provision (second args))
    (= (first args) "state") (pprint (read-string (slurp "state.edn")))
    :else (println
            (str "Build an uberjar.\n"
                 "Run the jar like so: java -jar farina-standalone.jar provision ./farina-standalone.jar"))))
