(ns farina.dwd
  (:require [clj-time.coerce :as c]))

(def intensity-mapping {"0" 0
                        "0-1" 1
                        "1" 2
                        "1-2" 3
                        "2" 4
                        "2-3" 5
                        "3" 6})

(defn kind-mapping [kind]
  (let [canonical (-> kind
                      (clojure.string/trim)
                      (clojure.string/lower-case)
                      (clojure.string/replace #"ä" "ae")
                      (clojure.string/replace #"ß" "ss"))
        mapping {"ambrosia" "ambrosia"
                 "beifuss" "mugwort"
                 "birke" "birch"
                 "erle" "alder"
                 "esche" "ash"
                 "graeser" "grass"
                 "hasel" "hazel"
                 "roggen" "rye"}]
    (get mapping canonical)))


(defn extract-dwd-pollen-data [rawdata]
  (flatten
    (let [content (get rawdata "content")
          last_update (get rawdata "last_update")
          date (c/to-long (re-find #"^\d{4}-\d{2}-\d{2}" last_update))]
      (if (nil? date) (throw (IllegalArgumentException. "Malformed timestamp in last_updated")))
      (for [raw-region content]
        (let [region_id (get raw-region "region_id")
              partregion_id (get raw-region "partregion_id")
              region (+ (* 1000 region_id) (java.lang.Math/abs partregion_id))]
          (for [[kind intensities] (get raw-region "Pollen")]
            {:date date
             :region region
             :kind (kind-mapping kind)
             :intensity (get intensity-mapping (get intensities "today"))}))))))
