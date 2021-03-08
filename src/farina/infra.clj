(ns farina.infra
  (:require [farina.infrastate :refer [spawn resource]]
             [farina.awsinfra :as awsinfra]
             [clojure.pprint :refer [pprint]]))

(def basename "farina")
(def bucketname basename)

(def infrastructure
  (list
        (resource :s3/rawdata
                 [bucketname]
                 []
                 (fn [deps bucketname]
                   (let [response (awsinfra/s3-bucket-crud bucketname :CreateBucket)]
                     (awsinfra/enable-bucket-versioning bucketname)
                     {:location response})))

        (resource :role/downloader
                  [basename
                   (str "/" basename "/")
                   {:Version "2012-10-17"
                    :Statement [{:Effect "Allow"
                                 :Principal {:Service ["lambda.amazonaws.com"]}
                                 :Action "sts:AssumeRole"}]}]
                  []
                  (fn [deps rolename path policy]
                    (awsinfra/role-crud rolename path policy :CreateRole)))

        (resource :role-policy/downloader
                  []
                  [:role/downloader :s3/rawdata]
                  (fn [deps]
                    (awsinfra/put-role-policy
                      (get-in deps [:role/downloader :resource :RoleName])
                      "farina-downloader-s3-access"
                      {:Version "2012-10-17"
                       :Statement [{:Effect "Allow"
                                    :Action ["s3:PutObject", "s3:GetObject", "s3:DeleteObject"]
                                    :Resource [(str
                                                 "arn:aws:s3:::"
                                                 (get-in deps [:s3/rawdata :inputs 0])
                                                 "/*")]}]})))
        ))

(defn provision []
  (let [before-state (read-string (slurp "state.edn"))
        state (spawn before-state (reverse infrastructure))]
    (spit "state.edn" (pr-str state))
    (pprint state)))
