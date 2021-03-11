(ns farina.infrastate
  (:require [clojure.data :refer [diff]]))

(defn spawn [initial-state brood]
  (let [everything (apply comp brood)]
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

; Suggested resource structure
; {:inputs []
;  :resource {}
;  :state :spawned}

(defn resource [rname ispec dspec breeder]
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
              (do
                (println "WARN:" rname "needs update, but we don't handle that")
                resource)

              (nil? resource)
              {:resource (breeder deps inputs)
               :inputs inputs
               :state :spawned})))))))
