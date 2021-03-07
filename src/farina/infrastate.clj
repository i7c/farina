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

(defn resource [rname inputs deps breeder]
  (fn [state] state
    (let [resource (rname state)
          rstate (:state resource)
          [current should _] (diff (:inputs resource) inputs)]

      (assoc
        state
        rname
        (cond
          ; resource already exists
          (= rstate :spawned)
          (if (not-every? nil? [current should])
            ; input changes happened
            {:inputs (:inputs resource)
             :resource (:resource resource)
             :state :needs-update}
            ; input and existing are identical
            resource)
          ; resource is in "needs update" state
          (= rstate :needs-update)
          (do
            (println "WARN:" rname "needs update, but we don't handle that")
            resource)
          ; resource is in any other state
          :else
          (let [resolved-deps (into {} (map #(do [% (% state)]) deps))
                deps-ready (every? some? (vals resolved-deps))]
            (if deps-ready
              ; deps are resolved, so run the breeder
              {:resource (apply breeder resolved-deps inputs)
               :inputs inputs
               :state :spawned}
              ; else
              {:resource nil
               :inputs inputs
               :state :unresolved-deps})))))))
