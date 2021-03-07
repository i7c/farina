(ns farina.infrastate-test
  (:require [clojure.test :refer :all]
            [clojure.data :refer [diff]]
            [farina.infrastate :refer :all]))

(deftest empty-state-and-empty-infra-returns-empty-state
  (let [resulting (spawn {} [])
        [a b _] (diff resulting {})]

    (is (and (nil? a) (nil? b)))))

(deftest state-accumulates
  (let [brood [(fn [state] (assoc state :a 10))
               (fn [state] (assoc state :b (+ (:a state) 5)))]

        result (spawn {} brood)
        [a b _] (diff result {:a 10 :b 15})]

    (is (and (nil? a) (nil? b)))))

(deftest missing-dependencies-iterate-out
  (let [brood [(fn [state]
                 (if-let [dep (:b state)]
                   (assoc state :a (* dep 2))
                   state))

               (fn [state] (assoc state :b 10))]

        result (spawn {} brood)
        [a b _] (diff result {:a 20 :b 10})]

    (is (and (nil? a) (nil? b)))))
