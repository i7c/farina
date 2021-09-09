(ns farina.downloader-infra
  (:require [farina.infrastate :refer [definfra res resource dep resolve-inputs]]
            [farina.awsinfra :as ai]
            [farina.awsclient :refer [iam lambda]]
            [clojure.data.json :refer [write-str]]))

(def jarpath "target/uberjar/farina-0.1.0-SNAPSHOT-standalone.jar")

(definfra downloader
  (res :role/downloader
       :dspec [:basename :s3/rawdata]
       :ispec
       {:RoleName (dep :basename)
        :Path (str "/" (dep :basename) "/")
        :AssumeRolePolicyDocument
        (write-str {:Version "2012-10-17"
                    :Statement
                    [{:Effect "Allow"
                      :Principal {:Service ["lambda.amazonaws.com"]}
                      :Action "sts:AssumeRole"}]}
                   :escape-slash false)}
       :breeder (fn [d i] (ai/generic-request iam {:op :CreateRole :request i}))
       :after
       (fn [r d i]
         ; attach basic execution policy
         (ai/generic-request
           iam
           {:op :AttachRolePolicy
            :request
            {:RoleName (get-in r [:Role :RoleName])
             :PolicyArn
             "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"}})
         ; attach custom policy that allows for s3 operations
         (let [policy-doc-spec
               {:RoleName (dep :role/downloader :RoleName)
                :PolicyName "farina-downloader"
                :PolicyDocument
                (fn [d]
                  (write-str
                    {:Version "2012-10-17"
                     :Statement
                     [{:Effect "Allow"
                       :Action ["s3:PutObject", "s3:GetObject", "s3:DeleteObject"]
                       :Resource
                       [(str "arn:aws:s3:::" (get-in d [:s3/rawdata :inputs :bucketname]) "/*")]}
                      {:Action ["sqs:SendMessage"] :Effect "Allow" :Resource "*" }]}
                    :escape-slash false))}
               policy-doc (resolve-inputs d policy-doc-spec)]
           (ai/generic-request iam {:op :PutRolePolicy
                                    :request policy-doc}))
         r))

  (resource :lambda/downloader
            {:FunctionName (str basename "-downloader")
             :Role #(get-in % [:role/downloader :resource :Arn])
             :Runtime "java11"
             :Handler "farina.core::download"
             :MemorySize 512
             :Timeout 25
             :Environment
             #(do {:Variables
                   {"QUEUE_RAWDATA" (get-in % [:sqs/farina-rawdata :resource :QueueUrl])}})}
            [:role/downloader :sqs/farina-rawdata]
            (fn [d i]
              (ai/generic-request
                lambda
                {:op :CreateFunction
                 :request (assoc
                            i
                            :Code {:ZipFile (byte-streams/to-byte-array (java.io.File. jarpath))})}))
            :updater (fn [s d i] (ai/lambda-update-code (:FunctionName s) jarpath))
            :deleter ai/lambda-deleter)

  (resource :eventbridgerule/downloader
            {:name (str basename "-downloader")
             :schedule "rate(15 minutes)"
             :description "Schedules the farina downloader for pulling raw data"
             :role #(get-in % [:role/downloader :resource :Arn])}
            [:role/downloader]
            (fn [d i]
              (ai/create-eventbridge-rule
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
              (ai/add-lambda-permission
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
              (ai/put-eventbridge-rule-targets (:rule i) (:targets i)))
            :deleter (fn [r d i]
                       (ai/generic-request
                         awsclient/eb
                         {:op :RemoveTargets
                          :request {:Rule (:rule i)
                                    :Ids "farina-downloader"}}))))
