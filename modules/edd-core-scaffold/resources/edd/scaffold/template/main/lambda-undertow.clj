(ns projectname.main
  (:gen-class)
  (:require [edd.core :as edd]
            [edd.java-lambda-runtime.core :as runtime]
            [lambda.core :as lambda]
            [projectname.filters :as filters]
            [projectname.service :as service]))

(def undertow-runtime "edd.undertow.core/start")

(runtime/start
 (service/init {})
 edd/handler
 :filters filters/filters)

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
