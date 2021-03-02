(ns farina.core
  (:require [farina.infrastructure :as infra])
  (:gen-class
    :methods [^:static [handler [] String]]))

(defn -handler []
  (str "Hello Farina"))

(defn -main [& args]
  (cond
    (= (first args) "provision") (infra/setup-infrastructure "farina")))
