(ns farina.grafana-querier-test
  (:require [clojure.test :refer :all]
            [farina.grafana-querier :refer :all]
            [clojure.data.json :as json]
            [clojure.java.io :as io]))

(defn request-in [path]
  (io/reader (char-array (json/write-str {:requestContext {:http {:path path}}}))))

(defn test-request [path]
  (let [raw (java.io.StringWriter.)
        _ (-handleRequest nil (request-in path) (io/writer raw) nil)]
    (json/read-str (.toString raw))))

(deftest query-root-path
  (is (= (test-request "/") "OK")))

(deftest query-annotations
  (is (= (test-request "/annotations") (list))))

(deftest query-search
  (is (= (test-request "/search") (list "pollen"))))
