(ns projectname.filters
  "Runtime filters, (fn [ctx request chain]) around the handler, first one
   outermost. The runtimes decode HTTP and queue requests themselves; an
   HTTP request needs a filter resolving its user."
  (:require [projectname.auth :as auth]))

(def filters
  [auth/authenticate])
