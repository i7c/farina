(ns farina.dwd-test
  (:require [clojure.test :refer :all]
            [clojure.data :refer [diff]]
            [farina.dwd :refer :all]))

(def rawdata
  {"content" [{"region_id" 120
               "partregion_name" "Bayern n. der Donau, o. Bayr. Wald, o. Mainfranken"
               "region_name" "Bayern"
               "partregion_id" 123
               "Pollen" {"Birke" {"dayafter_to" "-1" "today" "0" "tomorrow" "0"}
                         "Erle" {"dayafter_to" "-1" "tomorrow" "3" "today" "2-3"}}}

              {"Pollen" {"Erle" {"dayafter_to" "-1" "tomorrow" "3" "today" "3"}
                         "Birke" {"today" "1-2" "tomorrow" "0" "dayafter_to" "-1"}}
               "partregion_id" -1
               "region_name" "Mecklenburg-Vorpommern "
               "region_id" 20
               "partregion_name" ""}
              ]
   "sender" "Deutscher Wetterdienst - Medizin-Meteorologie",
   "next_update" "2021-03-01 11:00 Uhr"
   "legend" {"id1_desc" "keine Belastung"
             "id6_desc" "mittlere bis hohe Belastung"
             "id4" "1-2"
             "id3_desc" "geringe Belastung"
             "id7_desc" "hohe Belastung"
             "id7" "3"
             "id1" "0"
             "id5_desc" "mittlere Belastung"
             "id6" "2-3"
             "id4_desc" "geringe bis mittlere Belastung"
             "id5" "2"
             "id3" "1"
             "id2" "0-1"
             "id2_desc" "keine bis geringe Belastung"}
   "name" "Pollenflug-Gefahrenindex für Deutschland ausgegeben vom Deutschen Wetterdienst"
   "last_update" "2021-02-28 11:00 Uhr"}
  )

(def expected
  (list
    {:date 1614470400000
     :region 120123
     :kind "birch"
     :intensity 0}
    {:date 1614470400000
     :region 120123
     :kind "alder"
     :intensity 5}
    {:date 1614470400000
     :region 20001
     :kind "alder"
     :intensity 6}
    {:date 1614470400000
     :region 20001
     :kind "birch"
     :intensity 3}))

(deftest extract-dwd-data
  (let [data (extract-dwd-pollen-data rawdata)
        [a b _] (diff expected data)]
    (is (nil? a))
    (is (nil? b))))

(deftest map-intensities
  (is (= (get intensity-mapping "0") 0))
  (is (= (get intensity-mapping "0-1") 1))
  (is (= (get intensity-mapping "1") 2))
  (is (= (get intensity-mapping "1-2") 3))
  (is (= (get intensity-mapping "2") 4))
  (is (= (get intensity-mapping "2-3") 5))
  (is (= (get intensity-mapping "3") 6)))

(deftest map-plant-kindes
  (is (= (kind-mapping "AMBROSIA") "ambrosia"))
  (is (= (kind-mapping "beiFUSS") "mugwort"))
  (is (= (kind-mapping "bEIFuß") "mugwort"))
  (is (= (kind-mapping "BiRkE") "birch"))
  (is (= (kind-mapping "Erle") "alder"))
  (is (= (kind-mapping "esche") "ash"))
  (is (= (kind-mapping "Graeser") "grass"))
  (is (= (kind-mapping "Gräser") "grass"))
  (is (= (kind-mapping " HASEL ") "hazel"))
  (is (= (kind-mapping "  roGGen") "rye")))
