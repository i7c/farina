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

; Suggested resource structure
; {:inputs []
;  :resource {}
;  :state :spawned}

(defn spawner [sname inputs deps breeder]
  (fn [state] state
    (let [resource (sname state)]

      (assoc state sname
             (if (= (:state resource) :spawned)

               ; (= :state :spawned)
               (let [[a b _] (diff (:inputs resource) inputs)]
                 (if (not-every? nil? [a b])
                   ; changes to inputs were made
                   {:inputs (:inputs resource)
                    :resource (:resource resource)
                    :state :needs-update}
                   ; everything is the same
                   resource))

               ; (not= state :spawned)
               (let [resolved-deps (into {} (map #(do [% (% state)]) deps))
                     deps-ready (every? some? (vals resolved-deps))]
                 (if deps-ready
                   {:resource (apply breeder resolved-deps inputs)
                    :inputs inputs
                    :state :spawned}
                   ;else
                   {:resource nil
                    :inputs inputs
                    :state :unresolved-deps})))))))
