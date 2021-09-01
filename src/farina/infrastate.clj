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
       (filter #(let [deps (get (second %) :depends-on)
                      deps (if (vector? deps) (set deps) (set nil))]
                  (contains? deps me)))
       (map first)))

; Suggested resource structure
; {:inputs []
;  :resource {}
;  :state :spawned}

(defn resource [rname ispec dspec breeder & {:keys [updater deleter]
                                             :or {updater nil
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
                 :inputs inputs
                 :state :spawned}
                (do
                  (println "WARN:" rname "needs update, but there is no updater")
                  resource))

              (= rstate :delete)
              (if (some? deleter)
                {:resource (deleter (:resource resource) deps inputs)
                 :inputs inputs
                 :state :deleted}
                (do
                  (println "WARN:" rname "is to be deleted, but there is no deleter")
                  resource))

              (= rstate :deleted) resource

              (nil? resource)
              {:resource (breeder deps inputs)
               :inputs inputs
               :state :spawned})))))))
