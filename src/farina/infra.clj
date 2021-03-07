(ns farina.infra
  (:require [farina.infrastate :refer [spawn resource]]
             [farina.awsinfra :as awsinfra]))

(def basename "farina")
(def bucketname basename)

(def infrastructure
  (list
        (resource :s3/rawdata
                 [bucketname]
                 []
                 (fn [deps bucketname]
                   (let [response (awsinfra/s3-bucket-crud bucketname :CreateBucket)]
                     (awsinfra/enable-bucket-versioning bucketname)
                     {:location response})))
        ))

(defn provision []
  (let [before-state (read-string (slurp "state.edn"))
        state (spawn before-state (reverse infrastructure))]
    (spit "state.edn" (pr-str state))
    (print state)))
