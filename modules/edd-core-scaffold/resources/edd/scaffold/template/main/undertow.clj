(ns projectname.main
  (:gen-class)
  (:require [edd.core :as edd]
            [lambda.core :as lambda]
            [projectname.filters :as filters]
            [projectname.service :as service]))

(def undertow-runtime "edd.undertow.core/start")

(defn start
  [ctx & opts]
  (apply lambda/start
         ctx
         edd/handler
         :filters filters/filters
         opts))

(defn -main
  [& _args]
  (start
   (assoc (service/init {})
          :edd/runtime (System/getProperty "edd.runtime" undertow-runtime))))
