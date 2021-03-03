(ns farina.core
  (:require [farina.infrastructure :as infra]
            [clojure.string :as s])
  (:gen-class
    :methods [^:static [handler [] String]]))

(defn -handler []
  (str "Hello Farina"))

(defn -main [& args]
  (cond
    (and (= (count args) 2)
         (= (first args) "provision")) (infra/setup-infrastructure "farina" (second args))
    :else (println
            (str "Build an uberjar.\n"
                 "Run the jar like so: java -jar farina-standalone.jar provision ./farina-standalone.jar"))))
