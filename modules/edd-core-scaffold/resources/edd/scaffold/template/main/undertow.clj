(ns projectname.main
  (:gen-class)
  (:require [edd.core :as edd]
            [edd.undertow.core :as undertow]
            [projectname.filters :as filters]
            [projectname.service :as service]))

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
