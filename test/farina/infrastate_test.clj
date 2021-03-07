(ns farina.infrastate-test
  (:require [clojure.test :refer :all]
            [clojure.data :refer [diff]]
            [farina.infrastate :refer :all]))

(deftest empty-state-and-empty-infra-returns-empty-state
  (let [result (spawn {} [])
        [a b _] (diff result {:outcome :complete})]

    (is (and (nil? a) (nil? b)))))

(deftest state-accumulates
  (let [brood [(fn [state] (assoc state :b (+ (:a state) 5)))
               (fn [state] (assoc state :a 10))]
        result (spawn {} brood)
        [a b _] (diff result {:a 10 :b 15 :outcome :complete})]

    (is (and (nil? a) (nil? b)))))

(deftest missing-dependencies-iterate-out
  (let [brood [(fn [state] (assoc state :b 10))
               (fn [state]
                 (if-let [dep (:b state)]
                   (assoc state :a (* dep 2))
                   state))]
        result (spawn {} brood)
        [a b _] (diff result {:a 20 :b 10 :outcome :complete})]

    (is (and (nil? a) (nil? b)))))

(deftest cancel-if-state-is-never-stable
  (let [brood [(fn [state] (assoc state :a (+ (or (:a state) 0) 1)))]
        result (spawn {} brood)
        [a b _] (diff result {:a 1001 :outcome :partial})]

    (is (and (nil? a) (nil? b)))))

(deftest spawn-functions-applied-in-reverse-order
  (let [brood (-> '()
                  (conj (fn [state] (assoc state :a (or (:a state) (inc (apply max (vals state)))))))
                  (conj (fn [state] (assoc state :b (or (:b state) (inc (apply max (vals state)))))))
                  (conj (fn [state] (assoc state :c (or (:c state) (inc (apply max (vals state))))))))
        result (spawn {:z 1} brood)
        [a b _] (diff result {:z 1 :a 2 :b 3 :c 4 :outcome :complete})]
    (is (nil? a))
    (is (nil? b))))

(deftest spawner-checks-deps-and-resolves
  (let [brood (-> '()
                  (conj (fn [state] (if (> (:a state) 5)
                                      (assoc state :b {:x "Hello" :y 42})
                                      (assoc state :a (inc (:a state))))))
                  (conj (spawner :c
                                 ["foo"]
                                 [:b]
                                 (fn [deps i1]
                                   {:z i1
                                                :x (get-in deps [:b :x])
                                                :y (get-in deps [:b :y])}))))

        result (spawn {:a 1} brood)
        [a b _] (diff result {:outcome :complete
                              :a 6
                              :b {:x "Hello" :y 42}
                              :c {:state :spawned
                                  :inputs ["foo"]
                                  :resource {:z "foo" :x "Hello" :y 42}}})]
    (is (nil? a))
    (is (nil? b))))

(deftest spawner-marks-unresolved-deps
  (let [brood (-> '()
                  (conj (spawner :a [] [:b] (fn [deps] {:x (:b deps)}))))
        result (spawn {} brood)
        [a b _] (diff result {:outcome :complete
                              :a {:state :unresolved-deps
                                  :inputs []
                                  :resource nil}})]
    (is (nil? a))
    (is (nil? b))))
