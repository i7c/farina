(ns farina.core
  (:require [clj-http.client :as client]
            [cognitect.aws.client.api :as aws]
            [cognitect.aws.credentials :as credentials])
  (:gen-class))

(def iam (aws/client {:api :iam
                      :region "eu-central-1"}))

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


(defn -main [& args]
  (println (get-or-create-aws-user "farina" "/farina/")))
