(ns farina.core
  (:require [clj-http.client :as client]
            [cognitect.aws.client.api :as aws]
            [cognitect.aws.credentials :as credentials])
  (:gen-class))

(def region "eu-central-1")
(def iam (aws/client {:api :iam :region region}))
(def s3 (aws/client {:api :s3 :region region}))

(defn create-aws-user [username path]
  (let [response (aws/invoke iam {:op :CreateUser
                                  :request {:UserName username
                                            :Path path}})
        {{{:keys [Code Message]} :Error} :ErrorResponse
         {:keys [Path UserName UserId] :as user} :User} response]
    (if (some? Code)
      (throw (IllegalStateException. Message))
      user)))

(defn get-or-create-aws-user [username path]
  (let [response (aws/invoke iam {:op :GetUser
                                  :request {:UserName username
                                            :Path path}})
        {{{:keys [Code Message]} :Error} :ErrorResponse
         {:keys [Path UserName UserId] :as user} :User} response]
    (if (some? Code)
      (if (= Code "NoSuchEntity")
        (create-aws-user username path)
        (throw (IllegalStateException. Message)))
      user)))

(defn create-s3-bucket [bucketname]
  (let [response (aws/invoke s3 {:op :CreateBucket
                                 :request {:Bucket bucketname
                                           :CreateBucketConfiguration {:LocationConstraint region}}})
        {{:keys [Code Message]} :Error
         :keys [Location]} response]
    (if (some? Code) (throw (IllegalStateException. Message)) Location)))

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

(defn -main [& args]
  (let [basename "farina"
        principal (get-or-create-aws-user basename (str "/" basename "/"))
        bucket (get-or-create-s3-bucket basename)]
    (println bucket)))
