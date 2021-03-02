(ns farina.core
  (:require [farina.infrastructure :as infra])
  (:gen-class))

(defn -main [& args]
  (cond
    (= (first args) "provision") (infra/setup-infrastructure "farina")))
