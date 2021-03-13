(ns farina.awsinfra
  (:require [farina.awsclient :refer [region eb iam lambda s3]]
            [clj-http.client :as client]
            [clojure.string :as s]
            [clojure.data.json :as json]
            [cognitect.aws.client.api :as aws]
            [byte-streams]))

(defn aws-user-crud [username path op]
  (let [response (aws/invoke @iam {:op op
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
  (let [response (aws/invoke @s3 {:op op
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
  (let [response (aws/invoke @s3 {:op :PutBucketVersioning
                                 :request {:Bucket bucketname
                                           :VersioningConfiguration {:Status "Enabled"}}})
        error (get response :cognitect.anomalies/category)]
    (if (some? error) (throw (IllegalStateException. "Could not enable S3 versioning")))))

(defn role-crud [rolename path policy op]
  (let [request (cond
                  (= op :CreateRole) {:RoleName rolename
                                      :Path path
                                      :AssumeRolePolicyDocument (json/write-str
                                                                  policy
                                                                  :escape-slash false)}
                  (= op :GetRole) {:RoleName rolename})
        response (aws/invoke @iam {:op op :request request})
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
  (let [response (aws/invoke @iam {:op :AttachRolePolicy
                                  :request {:RoleName rolename
                                            :PolicyArn policy}})
        code (get-in response [:ErrorResponse :Error :Code])
        message (get-in response [:ErrorResponse :Error :Message])]
    (if (some? code) (throw (IllegalStateException. message)))))

(defn
  put-role-policy
  "Create and attach an inline role policy"
  [rolename policyname policy]
  (let [response (aws/invoke @iam {:op :PutRolePolicy
                                  :request {:RoleName rolename
                                            :PolicyName policyname
                                            :PolicyDocument (json/write-str
                                                                  policy
                                                                  :escape-slash false)}})
        code (get-in response [:ErrorResponse :Error :Code])
        message (get-in response [:ErrorResponse :Error :Message])]
    (cond
      (nil? code) response
      :else (throw (IllegalStateException. message)))))

(defn create-lambda [fname role handler code]
  (let [response (aws/invoke @lambda {:op :CreateFunction
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
  (let [response (aws/invoke @lambda {:op :UpdateFunctionCode
                                     :request {:FunctionName fname
                                               :ZipFile code}})
        message (:message response)]
    (cond
      (some? message) (throw (IllegalStateException. message))
      :else response)))

(defn get-update-or-create-lambda [fname role handler code]
  (let [response (aws/invoke @lambda {:op :GetFunction
                                     :request {:FunctionName fname}})
        message (:Message response)]
    (cond
      (and
        (some? message)
        (s/starts-with? message "Function not found: ")) (create-lambda fname role handler code)
      (some? message) (throw (IllegalStateException. message))
      :else (update-lambda-code fname code))))

(defn add-lambda-permission [statement fname action principal source-arn]
  (let [response (aws/invoke @lambda {:op :AddPermission
                                     :request {:FunctionName fname
                                               :Action action
                                               :Principal principal
                                               :SourceArn source-arn
                                               :StatementId statement}})
        error (get response :cognitect.anomalies/category)]
    (if (some? error) (println (str response)))
    response))

(defn create-eventbridge-rule [rulename schedule desc rolearn]
  (let [response (aws/invoke @eb {:op :PutRule
                                 :request {:Name rulename
                                           :ScheduleExpression schedule
                                           :State "ENABLED"
                                           :Description desc
                                           :Role rolearn}})
        error (get response :cognitect.anomalies/category)]
    (if (some? error) (throw (IllegalStateException. "Could not create EventBridge rule")))
    response))

(defn put-eventbridge-rule-targets [rule targets]
  (let [response (aws/invoke @eb {:op :PutTargets
                                 :request {:Rule rule
                                           :Targets targets}})
        failed-count (get response :FailedEntryCount)
        failed-entries (get response :FailedEntries)]
    (cond
      (some? failed-count) (if (> failed-count 0)
                             (throw (IllegalStateException. (str failed-entries))))
      :else response)))
