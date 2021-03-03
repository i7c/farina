(ns farina.infrastructure
  (:require [clj-http.client :as client]
            [clojure.string :as s]
            [clojure.data.json :as json]
            [cognitect.aws.client.api :as aws]
            [cognitect.aws.credentials :as credentials]
            [byte-streams]))

(def region "eu-central-1")
(def iam (aws/client {:api :iam :region region}))
(def s3 (aws/client {:api :s3 :region region}))
(def lambda (aws/client {:api :lambda :region region}))

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

(defn get-or-create-aws-user [username path]
  (aws-user-crud username path :GetUser))

(defn create-s3-bucket [bucketname]
  (let [response (aws/invoke s3 {:op :CreateBucket
                                 :request {:Bucket bucketname
                                           :CreateBucketConfiguration {:LocationConstraint region}}})
        {{:keys [Code Message]} :Error
         :keys [Location]} response]
    (cond
      (nil? Code) Location
      :else (throw (IllegalStateException. Message)))))

(defn get-or-create-s3-bucket [bucketname]
  (let [response (aws/invoke s3 {:op :GetBucketLocation
                                 :request {:Bucket bucketname}})
        {{:keys [Code Message]} :Error} response]
    (cond
      (nil? Code) (str "http://" bucketname ".s3.amazonaws.com/")
      (= Code "AccessDenied") (throw (IllegalStateException.
                                       (str "Bucket is owned by someone else: " Message)))
      (= Code "NoSuchBucket") (create-s3-bucket bucketname)
      :else (throw (IllegalStateException. Message)))))

(defn put-user-policy [username policyname policy]
  (let [response (aws/invoke iam {:op :PutUserPolicy
                                  :request {:UserName username
                                            :PolicyName policyname
                                            :PolicyDocument (json/write-str
                                                              policy
                                                              :escape-slash false)}})
        code (get-in response [:ErrorResponse :Error :Code])
        message (get-in response [:ErrorResponse :Error :Message])]
    (cond
      (nil? code) (:PutUserPolicyResponse response)
      :else (throw (IllegalStateException. message)))))

(defn create-role [rolename path policy]
  (let [response (aws/invoke iam {:op :CreateRole
                                  :request {:RoleName rolename
                                            :Path path
                                            :AssumeRolePolicyDocument (json/write-str policy
                                                                                      :escape-slash false)}})
        role (:Role response)
        code (get-in response [:ErrorResponse :Error :Code])
        message (get-in response [:ErrorResponse :Error :Message])]
    (cond
      (nil? code) role
      :else (throw (IllegalStateException. message)))))

(defn get-or-create-role [rolename path policy]
  (let [response (aws/invoke iam {:op :GetRole
                                  :request {:RoleName rolename}})
        role (:Role response)
        code (get-in response [:ErrorResponse :Error :Code])
        message (get-in response [:ErrorResponse :Error :Message])]
    (cond
      (nil? code) role
      (= code "NoSuchEntity") (create-role rolename path policy)
      :else (throw (IllegalStateException. message)))))

(defn attach-role-policy [rolename policy]
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
                                               :MemorySize 1024
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

(defn setup-infrastructure [basename jarpath]
  (let [bucketname basename
        principal (get-or-create-aws-user basename (str "/" basename "/"))
        bucket (get-or-create-s3-bucket bucketname)
        s3policy (put-user-policy
                   (:UserName principal)
                   "farina-s3"
                   {:Version "2012-10-17"
                    :Statement [{:Effect "Allow"
                                 :Action "*"
                                 :Resource (str "arn:aws:s3:::" bucketname "/*")}]})
        execrole (get-or-create-role
                   basename
                   (str "/" basename "/")
                   {:Version "2012-10-17"
                    :Statement [{:Effect "Allow"
                                 :Principal {:Service ["lambda.amazonaws.com"]}
                                 :Action "sts:AssumeRole"
                                 }]})
        rpolicies (doall (map
                           (partial attach-role-policy (:RoleName execrole))
                           ["arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"]))
        downloader (get-update-or-create-lambda (str basename "-downloader")
                              (:Arn execrole)
                              "farina.core::handler"
                              (byte-streams/to-byte-array (java.io.File. jarpath)))]))

