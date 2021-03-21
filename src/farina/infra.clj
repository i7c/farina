(ns farina.infra
  (:require [farina.infrastate :refer [spawn resource]]
             [farina.awsinfra :as awsinfra]
             [farina.awsclient :as awsclient]
             [clojure.data.json :as json]
             [clojure.pprint :refer [pprint]]
             [clojure.data :refer [diff]]))

(def basename "farina")
(def bucketname basename)

(def storage
  (list
    (resource :s3/rawdata
              {:bucketname bucketname}
              []
              (fn [d i]
                (let [response (awsinfra/s3-bucket-crud bucketname :CreateBucket)]
                  (awsinfra/enable-bucket-versioning bucketname)
                  {:location response})))))

(defn downloader [jarpath]
  (list
    ; TODO: replace with new function
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
              {:rolename (fn [d] (get-in d [:role/downloader :resource :RoleName]))
               :policyname "farina-downloader-s3-access"
               :policy (fn [d]
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

    (resource :lambda/downloader
              {:name (str basename "-downloader")
               :role #(get-in % [:role/downloader :resource :Arn])
               :handler "farina.core::download"}
              [:role/downloader]
              (fn [d i]
                (awsinfra/create-lambda (:name i) (:role i) (:handler i) (byte-streams/to-byte-array (java.io.File. jarpath)))))

    (resource :eventbridgerule/downloader
              {:name (str basename "-downloader")
               :schedule "rate(15 minutes)"
               :description "Schedules the farina downloader for pulling raw data"
               :role #(get-in % [:role/downloader :resrouce :Arn])}
              [:role/downloader]
              (fn [d i]
                (awsinfra/create-eventbridge-rule
                  (:name i)
                  (:schedule i)
                  (:description i)
                  (:role i))))

    (resource :lambdapermission/downloader
              {:statementid "eventbridge-can-invoke"
               :fname #(get-in % [:lambda/downloader :resource :FunctionName])
               :action "lambda:InvokeFunction"
               :principal "events.amazonaws.com"
               :source-arn #(get-in % [:eventbridgerule/downloader :resource :RuleArn])}
              [:lambda/downloader :eventbridgerule/downloader]
              (fn [d i]
                (awsinfra/add-lambda-permission
                  (:statementid i)
                  (:fname i)
                  (:action i)
                  (:principal i)
                  (:source-arn i))))

    (resource :eventbridgerule-target/downloader
              {:rule #(get-in % [:eventbridgerule/downloader :inputs :name]) ; TODO: replace this
               :targets #(do
                           [{:Id "farina-downloader"
                             :Arn (get-in % [:lambda/downloader :resource :FunctionArn])}])}
              [:eventbridgerule/downloader :lambda/downloader]
              (fn [d i]
                (awsinfra/put-eventbridge-rule-targets (:rule i) (:targets i))))))

(def cruncher
  (list
    (resource :dynamodb-table/farina
              {:TableName (str basename "-germany")
               :AttributeDefinitions [{:AttributeName "space" :AttributeType "S"}
                                      {:AttributeName "date" :AttributeType "N"}]
               :KeySchema [{:AttributeName "space" :KeyType "HASH"}
                           {:AttributeName "date" :KeyType "RANGE"}]
               :BillingMode "PAY_PER_REQUEST"}
              []
              (fn [d i]
                (awsinfra/generic-request
                  awsclient/dynamo
                  {:op :CreateTable :request i})))

    (resource :role/cruncher
              {:RoleName (str basename "-cruncher")
               :Path (str "/" basename "/")
               :AssumeRolePolicyDocument
               (json/write-str {:Version "2012-10-17"
                                :Statement [{:Effect "Allow"
                                             :Principal {:Service ["lambda.amazonaws.com"]}
                                             :Action "sts:AssumeRole"}]}
                               :escape-slash false)}
              []
              (fn [d i]
                (let [role (awsinfra/generic-request awsclient/iam {:op :CreateRole :request i})]
                  (awsinfra/generic-request
                    awsclient/iam
                    {:op :AttachRolePolicy
                     :request {:RoleName (get-in role [:Role :RoleName])
                               :PolicyArn "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"}})
                  role)))

    (resource :role-policy/cruncher
              {:RoleName #(get-in % [:role/cruncher :resource :Role :RoleName])
               :PolicyName "farina-cruncher-s3-and-dynamodb"
               :PolicyDocument #(do (json/write-str
                                      {:Version "2012-10-17"
                                       :Statement [{:Effect "Allow"
                                                    :Action ["s3:GetObject"]
                                                    :Resource [(str
                                                                 "arn:aws:s3:::"
                                                                 (get-in % [:s3/rawdata :inputs :bucketname])
                                                                 "/*")]}
                                                   {:Effect "Allow"
                                                    :Action ["dynamodb:BatchWriteItem"]
                                                    :Resource [(get-in
                                                                 %
                                                                 [:dynamodb-table/farina
                                                                  :resource
                                                                  :TableDescription
                                                                  :TableArn])]}]}
                                      :escape-slash false))}
              [:role/cruncher :s3/rawdata :dynamodb-table/farina]
              (fn [d i]
                (awsinfra/generic-request
                  awsclient/iam {:op :PutRolePolicy :request i})))
    ))

(defn state [] (read-string (slurp "state.edn")))

(defn infra [jarpath] (flatten [storage (downloader jarpath) cruncher]))

(defn provision-infra [infra]
  (let [before-state (state)
        after-state (spawn before-state (reverse infra))]
    (spit "state.edn" (pr-str after-state))
    (pprint state)))

(defn provision [jarpath]
  (provision-infra (infra jarpath)))

(defn manipulate-state-dry-run [f]
  (let [before-state (state)
        after-state (spawn before-state (list f))
        [before after _] (diff before-state after-state)]
    (pr-str {:before (keys before)
             :after (keys after)})))
