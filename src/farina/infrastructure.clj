(ns farina.infrastructure
  (:require [clj-http.client :as client]
            [clojure.string :as s]
            [clojure.data.json :as json]
            [cognitect.aws.client.api :as aws]
            [byte-streams]))

(def region "eu-central-1")
(def iam (aws/client {:api :iam :region region}))
(def s3 (aws/client {:api :s3 :region region}))
(def lambda (aws/client {:api :lambda :region region}))
(def eb (aws/client {:api :eventbridge :region region}))

(defn aws-user-crud [username path op]
  (let [response (aws/invoke iam {:op op
                                  :request {:UserName username
                                            :Path path}})
        user (:User response)
        code (get-in response [:ErrorResponse :Error :Code])
        message (get-in response [:ErrorResponse :Error :Message])]
    (cond
      (nil? code) user
      (and (= op :GetUser) (= code "NoSuchEntity")) (aws-user-crud username path :CreateUser)
      :else (throw (IllegalStateException. message)))))

(defn
  get-or-create-aws-user
  "Retrieves a user anyways, i.e. creates it first when it does not exist yet."
  [username path]
  (aws-user-crud username path :GetUser))

(defn s3-bucket-crud [bucketname op]
  (let [response (aws/invoke s3 {:op op
                                 :request {:Bucket bucketname
                                           :CreateBucketConfiguration {:LocationConstraint region}}})
        {{:keys [Code Message]} :Error} response]
    (cond
      (nil? Code) (str "http://" bucketname ".s3.amazonaws.com/")
      (= Code "AccessDenied") (throw (IllegalStateException.
                                       (str "Bucket is owned by someone else: " Message)))
      (and
        (= Code "NoSuchBucket")
        (= op :GetBucketLocation)) (s3-bucket-crud bucketname :CreateBucket)
      :else (throw (IllegalStateException. Message)))))

(defn
  get-or-create-s3-bucket
  "Gets a bucket's location as URL anyway, i.e. creates it if it doesn't exist yet."
  [bucketname]
  (s3-bucket-crud bucketname :GetBucketLocation))

(defn
  enable-bucket-versioning
  "Enable bucket versioning"
  [bucketname]
  (let [response (aws/invoke s3 {:op :PutBucketVersioning
                                 :request {:Bucket bucketname
                                           :VersioningConfiguration {:Status "Enabled"}}})
        error (get response :cognitect.anomalies/category)]
    (if (some? error) (throw (IllegalStateException. "Could not enable S3 versioning")))))

(defn role-crud [rolename path policy op]
  (let [response (aws/invoke iam {:op op
                                  :request {:RoleName rolename
                                            :Path path
                                            :AssumeRolePolicyDocument (json/write-str policy
                                                                                      :escape-slash false)}})
        role (:Role response)
        code (get-in response [:ErrorResponse :Error :Code])
        message (get-in response [:ErrorResponse :Error :Message])]
    (cond
      (nil? code) role
      (= code "NoSuchEntity") (role-crud rolename path policy :CreateRole)
      :else (throw (IllegalStateException. message)))))

(defn
  get-or-create-role
  "Gets a role anyways, i.e. creating it if it does not exist yet."
  [rolename path policy]
  (role-crud rolename path policy :GetRole))

(defn
  attach-role-policy
  "Attach a policy to an existing role."
  [rolename policy]
  (let [response (aws/invoke iam {:op :AttachRolePolicy
                                  :request {:RoleName rolename
                                            :PolicyArn policy}})
        code (get-in response [:ErrorResponse :Error :Code])
        message (get-in response [:ErrorResponse :Error :Message])]
    (if (some? code) (throw (IllegalStateException. message)))))

(defn create-lambda [fname role handler code]
  (let [response (aws/invoke lambda {:op :CreateFunction
                                     :request {:FunctionName fname
                                               :Role role
                                               :Runtime "java11"
                                               :Handler handler
                                               :MemorySize 512
                                               :Timeout 25
                                               :Code {:ZipFile code}}})
        message (:message response)]
    (cond
      (some? message) (throw (IllegalStateException. message))
      :else response)))

(defn update-lambda-code [fname code]
  (let [response (aws/invoke lambda {:op :UpdateFunctionCode
                                     :request {:FunctionName fname
                                               :ZipFile code}})
        message (:message response)]
    (cond
      (some? message) (throw (IllegalStateException. message))
      :else response)))

(defn get-update-or-create-lambda [fname role handler code]
  (let [response (aws/invoke lambda {:op :GetFunction
                                     :request {:FunctionName fname}})
        message (:Message response)]
    (cond
      (and
        (some? message)
        (s/starts-with? message "Function not found: ")) (create-lambda fname role handler code)
      (some? message) (throw (IllegalStateException. message))
      :else (update-lambda-code fname code))))

(defn create-eventbridge-rule [rulename schedule desc rolearn]
  (let [response (aws/invoke eb {:op :PutRule
                                 :request {:Name rulename
                                           :ScheduleExpression schedule
                                           :State "ENABLED"
                                           :Description desc
                                           :Role rolearn}})
        error (get response :cognitect.anomalies/category)]
    (if (some? error) (throw (IllegalStateException. "Could not create EventBridge rule")))
    response))

(defn setup-infrastructure [basename jarpath]
  (let [bucketname basename

        principal (get-or-create-aws-user basename (str "/" basename "/"))

        bucket (get-or-create-s3-bucket bucketname)
        _ (enable-bucket-versioning bucketname)

        execrole (get-or-create-role
                   basename
                   (str "/" basename "/")
                   {:Version "2012-10-17"
                    :Statement [{:Effect "Allow"
                                 :Principal {:Service ["lambda.amazonaws.com"]}
                                 :Action "sts:AssumeRole"}

                                {:Effect "Allow"
                                 :Action ["s3:PutObject", "s3:GetObject", "s3:DeleteObject"]
                                 :Resource [(str "arn:aws:s3:::" bucketname "/*")]}
                                ]})
        rp (attach-role-policy (:RoleName execrole) "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole")

        downloader (get-update-or-create-lambda (str basename "-downloader")
                                                (:Arn execrole)
                                                "farina.core::download"
                                                (byte-streams/to-byte-array (java.io.File. jarpath)))

        download-scheduled-rule (create-eventbridge-rule
                                  "farina-download-rule"
                                  "rate(5 minutes)"
                                  "Schedules the farina downloader every 5 minutes"
                                  (:Arn execrole))]

    (println principal bucket execrole rp downloader download-scheduled-rule)))

