(ns farina.infrastate
  (:require [clojure.data :refer [diff]]))

(defn spawn [initial-state brood-or-fn & {:keys [beforefn afterfn]
                                          :or {beforefn identity
                                               afterfn (fn [bef aft] aft)}}]
  (let [brood (if (fn? brood-or-fn) (list brood-or-fn) brood-or-fn)
        intercepted (map #(fn [before]
                            (beforefn before)
                            (let [after (% before)]
                              (afterfn before after)
                              after)) brood)
        everything (apply comp intercepted)]
    (loop [state initial-state
           iter-remaining 1000]
      (let [new-state (everything state)
            [went came _] (diff state new-state)]
        (cond
          (and (nil? went) (nil? came)) (assoc new-state :outcome :complete)
          (<= iter-remaining 0) (assoc new-state :outcome :partial)
          :else (recur new-state (dec iter-remaining)))))))

(defn resolve-inputs [deps ispec]
  (into {}
        (map #(if (fn? (% ispec))
                [% ((% ispec) deps)]
                [% (% ispec)])
             (keys ispec))))

(defn dependants [state me]
  (->> state
       (seq)
       (filter #(let [res (second %)
                      deps (get res :depends-on)
                      deps (if (vector? deps) (set deps) (set nil))]
                  (and
                    (or (= (:state res) :needs-update) (= (:state res) :spawned))
                    (contains? deps me))))
       (map first)))

; Suggested resource structure
; {:inputs []
;  :resource {}
;  :state :spawned}


(defn res [rname & {:keys [ispec dspec breeder after updater deleter]
                    :or {ispec {}
                         dspec []
                         breeder (fn [d i] i)
                         after (fn [r d i] r)
                         updater nil
                         deleter nil}}]
  (fn [state]
    (assoc
      state
      rname
      (let [resource (rname state)
            rstate (:state resource)
            deps (into {} (map #(do [% (% state)]) dspec))]
        ; if deps are missing, just set resource state and stop
        (if (not-every? some? (vals deps))
          (assoc resource :state :unresolved-deps)
          (let [inputs (resolve-inputs deps ispec)
                [went came _] (diff (:inputs resource) inputs)]
            (cond
              (= rstate :spawned)
              (if (not-every? nil? [went came])
                (assoc resource :state :needs-update)
                resource)

              (= rstate :needs-update)
              (if (some? updater)
                {:resource (updater (:resource resource) deps inputs)
                 :depends-on dspec
                 :inputs inputs
                 :state :spawned}
                (do
                  (println "WARN:" rname "needs update, but there is no updater")
                  resource))

              (= rstate :delete)
              (if (some? deleter)
                (if (empty? (dependants state rname))
                  {:resource (deleter (:resource resource) deps inputs)
                   :depends-on dspec
                   :inputs inputs
                   :state :deleted}
                  resource)
                (do
                  (println "WARN:" rname "is to be deleted, but there is no deleter")
                  resource))

              (= rstate :deleted) resource
              (= rstate :failed) resource

              (nil? resource)
              (let [new-resource (breeder deps inputs)]
                (if (some? new-resource)
                  (try {:resource (after new-resource deps inputs)
                        :inputs inputs
                        :depends-on dspec
                        :state :spawned}
                       (catch Exception e
                         {:resource new-resource
                          :inputs inputs
                          :depends-on dspec
                          :state :failed}))
                  nil)))))))))

(defn resource
  ^:deprecated
  [rname ispec dspec breeder & {:keys [updater deleter]
                                :or {updater nil
                                     deleter nil}}]
  (res rname
       :ispec ispec
       :dspec dspec
       :breeder breeder
       :updater updater
       :deleter deleter))

(defmacro definfra [infraname & brood]
  `(def ~infraname
     (list ~@brood)))

(defn dep [depname & path]
  (print (type path))
  #(get-in % (conj path :resource depname)))
