(ns farina.infrastate
  (:require [clojure.data :refer [diff]]))

(defn spawn [initial-state brood]
  (let [everything (apply comp (reverse brood))]
    (loop [state initial-state]
      (let [new-state (everything state)
            [went came _] (diff state new-state)]
        (if (or (some? went) (some? came))
          (recur new-state)
          new-state)))))
