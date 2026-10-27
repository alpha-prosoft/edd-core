(ns projectname.auth
  (:require [aws.cognito :as cognito]))

(defn register
  "Authentication of HTTP requests."
  [ctx]
  (cognito/register ctx))
