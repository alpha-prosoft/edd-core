(ns projectname.main
  (:gen-class)
  (:require [edd.core :as edd]
            [edd.java-lambda-runtime.core :as lambda]
            [edd.undertow.core :as undertow]
            [projectname.filters :as filters]
            [projectname.service :as service]))

(lambda/start
 (service/init {})
 edd/handler
 :filters filters/filters)

(defn start
  [ctx & opts]
  (apply undertow/start
         ctx
         edd/handler
         :filters filters/filters
         opts))

(defn -main
  [& _args]
  (start (service/init {})))
