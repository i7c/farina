(ns farina.core
  (:require [farina.infrastructure :as infra])
  (:gen-class))

(defn -main [& args]
  (infra/setup-infrastructure "farina"))
