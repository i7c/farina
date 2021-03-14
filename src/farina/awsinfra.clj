(ns farina.awsinfra
  (:require [farina.awsclient :refer [region eb iam lambda s3 eks ec2]]
            [clj-http.client :as client]
            [clojure.string :as s]
            [clojure.data.json :as json]
            [cognitect.aws.client.api :as aws]
            [byte-streams]))

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

(defn generic-request [client op]
  (let [response (aws/invoke @client op)
        error (get response :cognitect.anomalies/category)]
    (cond (some? error) (throw (IllegalStateException. (str response)))
          :else response)))


(defn add-lambda-permission [statement fname action principal source-arn]
  (generic-request lambda {:op :AddPermission
                           :request {:FunctionName fname
                                     :Action action
                                     :Principal principal
                                     :SourceArn source-arn
                                     :StatementId statement}}))

(defn create-eventbridge-rule [rulename schedule desc rolearn]
  (generic-request eb {:op :PutRule
                       :request {:Name rulename
                                 :ScheduleExpression schedule
                                 :State "ENABLED"
                                 :Description desc
                                 :Role rolearn}}))

(defn put-eventbridge-rule-targets [rule targets]
  (generic-request eb {:op :PutTargets :request {:Rule rule :Targets targets}}))

(defn create-vpc [cidrblock]
  (generic-request ec2 {:op :CreateVpc :request {:CidrBlock cidrblock}}))

(defn create-subnet [vpc cidrblock az]
  (generic-request ec2 {:op :CreateSubnet
                        :request {:VpcId vpc
                                  :CidrBlock cidrblock
                                  :AvailabilityZone az}}))

(defn create-role [rolename path service]
  (let [policydoc
        {:Version "2012-10-17"
         :Statement [{:Effect "Allow"
                      :Principal {:Service [service]}
                      :Action "sts:AssumeRole"}]}]
    (generic-request iam {:op :CreateRole
                          :request {:RoleName rolename
                                    :Path path
                                    :AssumeRolePolicyDocument (json/write-str policydoc
                                                                              :escape-slash false)}})))

(defn create-eks-cluster [clustername role vpcconfig]
  (generic-request eks {:op :CreateCluster
                        :request {:name clustername
                                  :roleArn role
                                  :resourcesVpcConfig vpcconfig}}))
