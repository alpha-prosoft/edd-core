(ns lambda.request)

(def ^:dynamic *request*)

(defn is-scoped
  "Used for example when there is multiple realms or
  tenant implemented. Mostly to distinguish when running tests."
  []
  (:scoped @*request*))
