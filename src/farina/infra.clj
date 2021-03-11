(ns farina.infra
  (:require [farina.infrastate :refer [spawn resource]]
             [farina.awsinfra :as awsinfra]
             [clojure.pprint :refer [pprint]]))

(def basename "farina")
(def bucketname basename)

(def infrastructure
  (list
        (resource :s3/rawdata
                  {:bucketname bucketname}
                 []
                 (fn [d i]
                   (let [response (awsinfra/s3-bucket-crud bucketname :CreateBucket)]
                     (awsinfra/enable-bucket-versioning bucketname)
                     {:location response})))

        (resource :role/downloader
                  {:rolename basename
                   :path (str "/" basename "/")
                   :policy {:Version "2012-10-17"
                            :Statement [{:Effect "Allow"
                                         :Principal {:Service ["lambda.amazonaws.com"]}
                                         :Action "sts:AssumeRole"}]}}
                  []
                  (fn [d i]
                    (awsinfra/role-crud (:rolename i) (:path i) (:policy i) :CreateRole)))

        (resource :role-policy/downloader
                  {:rolename (fn [d i] (get-in d [:role/downloader :resource :RoleName]))
                   :policyname "farina-downloader-s3-access"
                   :policy (fn [d i]
                             {:Version "2012-10-17"
                              :Statement [{:Effect "Allow"
                                           :Action ["s3:PutObject", "s3:GetObject", "s3:DeleteObject"]
                                           :Resource [(str
                                                        "arn:aws:s3:::"
                                                        (get-in d [:s3/rawdata :inputs :bucketname])
                                                        "/*")]}]})}
                  [:role/downloader :s3/rawdata]
                  (fn [d i]
                    (awsinfra/attach-role-policy (:rolename i) "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole")
                    (awsinfra/put-role-policy (:rolename i) (:policyname i) (:policy i))))

        ))

(defn provision []
  (let [before-state (read-string (slurp "state.edn"))
        state (spawn before-state (reverse infrastructure))]
    (spit "state.edn" (pr-str state))
    (pprint state)))
