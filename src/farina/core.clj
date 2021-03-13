(ns farina.core
  (:require [farina.infra :as infra]
            [farina.awsclient :refer [s3]]
            [clojure.string :as s]
            [clojure.data.json :as json]
            [cognitect.aws.client.api :as aws]
            [clj-http.client :as client])
  (:gen-class
    :methods [^:static [download [] String]]))

(defn -download []
  (let [raw (client/get
              "https://opendata.dwd.de/climate_environment/health/alerts/s31fg.json"
              {:accept :json})
        decoded (json/read-str (:body raw))
        date (re-find #"^\d{4}-\d{2}-\d{2}" (get decoded "last_update"))
        response (aws/invoke @s3 {:op :PutObject
                                 :request {:Bucket "farina"
                                           :Key (str "raw/" date)
                                           :Body (.getBytes (:body raw) "UTF-8")}})
        code (get-in response [:Error :Code])
        message (get-in response [:Error :Message])]
    (if (some? code) (throw (IllegalStateException. message)))
    (json/write-str response)))

(defn -main [& args]
  (cond
    (and (= (count args) 2)
         (= (first args) "provision")) (infra/setup-infrastructure "farina" (second args))
    :else (println
            (str "Build an uberjar.\n"
                 "Run the jar like so: java -jar farina-standalone.jar provision ./farina-standalone.jar"))))
