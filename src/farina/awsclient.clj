(ns farina.awsclient
  (:require [cognitect.aws.client.api :as aws]))

(def region "eu-central-1")

(def eb (delay (aws/client {:api :eventbridge :region region})))
(def ecs (delay (aws/client {:api :ecs :region region})))
(def iam (delay (aws/client {:api :iam :region region})))
(def lambda (delay (aws/client {:api :lambda :region region})))
(def s3 (delay (aws/client {:api :s3 :region region})))
