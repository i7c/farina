(ns farina.infra
  (:require [farina.infrastate :refer [spawn resource]]
             [farina.awsinfra :as awsinfra]
             [clojure.pprint :refer [pprint]]))

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

(def network
  (list
    (resource :ec2/vpc
              {:cidr "10.0.0.0/16"}
              []
              (fn [d i] (awsinfra/create-vpc (:cidr i))))

    (resource :ec2/eks-subnet1
              {:cidr "10.0.1.0/24"
               :vpc #(get-in % [:ec2/vpc :resource :Vpc :VpcId])
               :az "eu-central-1a"}
              [:ec2/vpc]
              (fn [d i] (awsinfra/create-subnet (:vpc i) (:cidr i) (:az i))))

    (resource :ec2/eks-subnet2
              {:cidr "10.0.2.0/24"
               :vpc #(get-in % [:ec2/vpc :resource :Vpc :VpcId])
               :az "eu-central-1b"}
              [:ec2/vpc]
              (fn [d i] (awsinfra/create-subnet (:vpc i) (:cidr i) (:az i))))
    ))

(def eks
  (list
    (resource :role/eks
              {:name (str basename "-eks")
               :path (str "/" basename "/")
               :service "eks.amazonaws.com"}
              []
              (fn [d i]
                (let [result (awsinfra/create-role (:name i) (:path i) (:service i))]
                  (awsinfra/attach-role-policy
                    (get-in result [:Role :RoleName])
                    "arn:aws:iam::aws:policy/AmazonEKSClusterPolicy")
                  result)))

    (resource :eks/cluster
              {:name basename
               :role #(get-in % [:role/eks :resource :Role :Arn])
               :vpc-config #(do {:endpointPublicAccess true
                                 :subnetIds [(get-in % [:ec2/eks-subnet1 :resource :Subnet :SubnetId])
                                             (get-in % [:ec2/eks-subnet2 :resource :Subnet :SubnetId])]})}
              [:role/eks :ec2/eks-subnet1 :ec2/eks-subnet2]
              (fn [d i]
                (awsinfra/create-eks-cluster (:name i) (:role i) (:vpc-config i))))
    ))

(defn provision [jarpath]
  (let [infrastructure (flatten [storage (downloader jarpath) network eks])
        before-state (read-string (slurp "state.edn"))
        state (spawn before-state (reverse infrastructure))]
    (spit "state.edn" (pr-str state))
    (pprint state)))
