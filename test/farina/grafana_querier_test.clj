(ns farina.grafana-querier-test
  (:require [clojure.test :refer :all]
            [farina.grafana-querier :refer :all]
            [clojure.data :refer [diff]]
            [clojure.data.json :as json]
            [clojure.java.io :as io]))

(def database
  {"120122-alder" {:Items [{:space {:S "120122-alder"}, :intensity {:N "0"}, :date {:N "1619827200000"}}
                           {:space {:S "120122-alder"}, :intensity {:N "0"}, :date {:N "1619740800000"}}
                           {:space {:S "120122-alder"}, :intensity {:N "0"}, :date {:N "1619654400000"}}]
                   :Count 3
                   :ScannedCount 3}
   "120122-ash" {:Items [{:space {:S "120122-ash"}, :intensity {:N "3"}, :date {:N "1619827200000"}}
                         {:space {:S "120122-ash"}, :intensity {:N "3"}, :date {:N "1619740800000"}}
                         {:space {:S "120122-ash"}, :intensity {:N "3"}, :date {:N "1619654400000"}}],
                 :Count 3,
                 :ScannedCount 3}})

(defn request-in [path query-params]
  (io/reader (char-array (json/write-str {:requestContext {:http {:path path}}
                                          :queryStringParameters query-params}))))

(defn test-request [path & {:keys [query-params] :or {query-params {}}}]
  (let [raw (java.io.StringWriter.)]
    (with-redefs [query-for-space #(get-in database [%])]
      (-handleRequest nil (request-in path query-params) (io/writer raw) nil)
      (json/read-str (.toString raw)))))

(deftest query-root-path
  (is (= (test-request "/") "OK")))

(deftest query-annotations
  (is (= (test-request "/annotations") (list))))

(deftest query-search
  (is (= (test-request "/search") (list "pollen"))))

(deftest query-raw-with-non-existent-space
  (is (= (test-request "/raw" :query-params {:space "100100-foo"}) (list))))

(deftest query-raw-with-data
  (let [response (test-request "/raw" :query-params {:space "120122-ash"})
        [a b _] (diff response [{"intensity" 3, "date" 1619827200000}
                                {"intensity" 3, "date" 1619740800000}
                                {"intensity" 3, "date" 1619654400000}])]
    (is (nil? a))
    (is (nil? b))))
