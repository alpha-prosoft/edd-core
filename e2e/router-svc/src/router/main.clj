(ns router.main
  (:gen-class)
  (:require [edd.java-lambda-runtime.core :as runtime]
            [edd.router.core :as router]
            [edd.sqs.distributor :as distributor]))

(runtime/start
 (router/register (distributor/register {}))
 router/handler)
