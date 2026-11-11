(ns projectname.auth
  (:require [aws.cognito :as cognito]))

(def authenticate
  "Runtime filter resolving the user of HTTP requests."
  cognito/authenticate)

(defn register
  "What authenticate needs on ctx."
  [ctx]
  (cognito/register ctx))
