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
