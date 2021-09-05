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

(deftest resource-checks-deps-and-resolves
  (let [brood (-> '()
                  (conj (fn [state] (if (> (:a state) 5)
                                      (assoc state :b {:x "Hello" :y 42})
                                      (assoc state :a (inc (:a state))))))
                  (conj (resource :c
                                  {:i1 "foo"}
                                  [:b]
                                  (fn [deps ins]
                                    {:z (ins :i1)
                                     :x (get-in deps [:b :x])
                                     :y (get-in deps [:b :y])}))))

        result (spawn {:a 1} brood)
        [a b _] (diff result {:outcome :complete
                              :a 6
                              :b {:x "Hello" :y 42}
                              :c {:state :spawned
                                  :depends-on [:b]
                                  :inputs {:i1 "foo"}
                                  :resource {:z "foo" :x "Hello" :y 42}}})]
    (is (nil? a))
    (is (nil? b))))

(deftest resource-marks-unresolved-deps
  (let [brood (-> '()
                  (conj (resource :a {} [:b] (fn [deps ins] {:x (:b deps)}))))
        result (spawn {} brood)
        [a b _] (diff result {:outcome :complete
                              :a {:state :unresolved-deps}})]
    (is (nil? a))
    (is (nil? b))))

(deftest unresolved-dependencies-resolve-on-retry
  (let [brood (-> '()
                  (conj (resource :a {} [:b] (fn [d i] {:other (get-in d [:b :resource])})))
                  (conj (resource :b {} [] (fn [d i] {:x 1 :y 2}))))
        state {:outcome :complete
               :a {:state :unresolved-deps
                   :inputs {}
                   :resource nil}}
        result (spawn state brood)
        [a b _] (diff result {:outcome :complete
                              :a {:state :spawned
                                  :inputs {}
                                  :depends-on [:b]
                                  :resource {:other {:x 1 :y 2}}}
                              :b {:state :spawned
                                  :inputs {}
                                  :depends-on []
                                  :resource {:x 1 :y 2}}})]
    (is (nil? a))
    (is (nil? b))))

(deftest input-change-leads-to-stable-resource-state
  (let [brood (resource :a {:i1 "foo"} [] (fn [deps ins] {:x (ins :i1)}))
        state {:outcome :complete
               :a {:state :spawned
                   :inputs {:i1 "bar"}
                   :resource {:x "bar"}}}

        result (spawn state brood)
        [a b _] (diff result {:outcome :complete
                              :a {:state :needs-update
                                  :inputs {:i1 "bar"}
                                  :resource {:x "bar"}}})]
    (is (nil? a))
    (is (nil? b))))


(deftest input-can-reference-dependencies
  (let [brood (list
                (resource :a {} [] (fn [deps ins] {:x 42}))
                (resource :b
                          {:i1 #(* 2 (get-in % [:a :resource :x]))}
                          [:a]
                          (fn [d i] {:x (i :i1)})))
        result (spawn {} brood)
        [a b _] (diff result {:outcome :complete
                              :a {:state :spawned
                                  :inputs {}
                                  :depends-on []
                                  :resource {:x 42}}
                              :b {:state :spawned
                                  :depends-on [:a]
                                  :inputs {:i1 84}
                                  :resource {:x 84}}})]
    (is (nil? a))
    (is (nil? b))))

(deftest input-resolver-never-called-when-dep-missing
  (let [brood (resource :a
                        {:i1 #(throw (IllegalStateException. "!"))}
                        [:b]
                        (fn [d i] nil))
        result (spawn {} brood)
        [a b _] (diff result {:outcome :complete
                              :a {:state :unresolved-deps}})]
    (is (nil? a))
    (is (nil? b))))

(deftest resolve-inputs-with-dependencies
  (let [deps {:r1 {:state :spawned
                   :inputs {}
                   :resource {:x "foo"}}}
        ispec {:a "bar"
               :b 42
               :c #(get-in % [:r1 :resource :x])}
        inputs (resolve-inputs deps ispec)
        [a b _] (diff inputs {:a "bar"
                              :b 42
                              :c "foo"})]
    (is (nil? a))
    (is (nil? b))))

(deftest updater-changes-resource
  (let [res (resource :foo
                      {:v 1}
                      []
                      (fn [d i] i)
                      :updater (fn [s d i] s))

        initial {}
        spawned (spawn initial res)

        [a1 b1 _] (diff spawned {:foo {:inputs {:v 1}
                                       :depends-on []
                                       :resource {:v 1}
                                       :state :spawned}
                                 :outcome :complete})

        updated-res (resource :foo
                              {:v 1 :useless 5}
                              []
                              (fn [d i] i)
                              :updater (fn [s d i]
                                         (assoc s :v 2)))
        updated (spawn spawned updated-res)

        [a2 b2 _] (diff updated {:foo {:inputs {:v 1 :useless 5}
                                       :depends-on []
                                       :resource {:v 2}
                                       :state :spawned}
                                 :outcome :complete})]

    (is (nil? a1))
    (is (nil? b1))
    (is (nil? a2))
    (is (nil? b2))))

(deftest deleter-deletes-flagged-resource
  (let [initial (spawn {} (list (resource :foo {:v 1} [] (fn [d i] i))))
        [a1 b1 _] (diff initial {:foo {:inputs {:v 1}
                                       :depends-on []
                                       :resource {:v 1}
                                       :state :spawned}
                                 :outcome :complete})

        after-marking (spawn initial (list #(assoc-in % [:foo :state] :delete)))

        after-deleting (spawn after-marking (list (resource :foo
                                                            {:v 1}
                                                            []
                                                            (fn [d i] i)
                                                            :deleter (fn [r d i] nil))))
        [a2 b2 _] (diff after-deleting {:foo {:inputs {:v 1}
                                              :depends-on []
                                              :resource nil
                                              :state :deleted}
                                        :outcome :complete})]
    (is (nil? a1))
    (is (nil? b1))
    (is (nil? a2))
    (is (nil? b2))))

(deftest find-all-dependants
  (let [state {:foo {:state :spawned :depends-on [:bar :baz]}
               :bar {:state :spawned :depends-on [:baz]}
               :nop {:state :spawned :depends-on [:bar]}
               :baz {:state :spawned :depends-on []}}

        dependants (dependants state :baz)]
    (is (= dependants [:foo :bar]))))

(deftest find-dependants-of-non-compliant-resources
  (let [state {:foo {:x 10}
               :bar {}}
        dependants (dependants state :baz)]
    (is (= dependants []))))

(deftest keep-deletemarked-resource-with-dependants
  (let [brood (list (resource :dependee {} [] (fn [d i] i)
                              :deleter (fn [r d i] (throw (IllegalStateException. "Deleted resource with dependants"))))
                    (resource :dependant {} [:dependee] (fn [d i] i)))
        initial (spawn {} brood)
        after-marking (spawn initial #(assoc-in % [:dependee :state] :delete))

        [a1 b1 _] (diff after-marking {:dependee {:inputs {} :depends-on [] :resource {} :state :delete}
                                       :dependant {:inputs {} :depends-on [:dependee] :resource {} :state :spawned}
                                       :outcome :complete})

        after-deleting (spawn after-marking brood)

        [a2 b2 _] (diff after-deleting {:dependee {:inputs {} :depends-on [] :resource {} :state :delete}
                                        :dependant {:inputs {} :depends-on [:dependee] :resource {} :state :spawned}
                                        :outcome :complete})]
    (is (nil? a1))
    (is (nil? b1))
    (is (nil? a2))
    (is (nil? b2))))

(deftest dep-resolves-paths-within-dependency
  (let [brood (list (res :foo
                         :ispec {:a {:b {:c 5}
                                     :d 10}})
                    (res :bar
                         :dspec [:foo]
                         :ispec {:v1 (dep :foo :a :b :c)
                                 :v2 (dep :foo :a :d)
                                 :v3 (dep :nop :x)}))
        state (spawn {} brood)]
    (is (= 5 (get-in state [:bar :resource :v1])))
    (is (= 10 (get-in state [:bar :resource :v2])))
    (is (nil? (get-in state [:bar :resource :v3])))))

(deftest after-fn-executes-after-successful-breeder
  (let [brood (res :foo
                   :ispec {:x 7}
                   :breeder (fn [d i] (assoc i :y 42))
                   :after (fn [r d i]
                            (is (= (get r :x) 7))
                            (is (= (get r :y) 42))
                            (assoc r :z 1337)))
        state (spawn {} brood)
        [a b _] (diff state {:foo {:resource {:x 7 :y 42 :z 1337}
                                   :state :spawned
                                   :inputs {:x 7}
                                   :depends-on []}
                             :outcome :complete})]
    (is (nil? a))
    (is (nil? b))))

(deftest after-fn-doesnt-execute-if-breeder-fails
  (let [brood (res :foo
                   :breeder (fn [d i] nil)
                   :after (fn [r d i] (throw (IllegalStateException. "after fn executed"))))]
    (spawn {} brood)))

(deftest resource-is-created-even-if-after-fn-failes
  (let [brood (res :foo
                   :ispec {:x 1}
                   :after (fn [r d i] (throw (IllegalStateException. "after fn fails"))))
        state (spawn {} brood)
        [a b _] (diff state {:outcome :complete
                             :foo {:resource {:x 1}
                                   :depends-on []
                                   :inputs {:x 1}
                                   :state :failed}})]
    (is (nil? a))
    (is (nil? b))))
