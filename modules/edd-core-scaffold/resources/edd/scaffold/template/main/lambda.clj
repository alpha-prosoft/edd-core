(ns projectname.main
  (:gen-class)
  (:require [edd.core :as edd]
            [edd.java-lambda-runtime.core :as runtime]
            [projectname.filters :as filters]
            [projectname.service :as service]))

(runtime/start
 (service/init {})
 edd/handler
 :filters filters/filters)
