(ns edd.view-store.s3
  (:require [edd.view-store.common :as common]
            [lambda.util :as util]
            [malli.core :as m]
            [malli.error :as me]
            [edd.view-store.common :refer [get-snapshot
                                           update-snapshot
                                           with-init]]
            [edd.view-store.impl.s3.main :as s3-main]
            [edd.view-store.impl.s3.mock :as s3-mock]))

(defmethod with-init s3-main/implementation [& params] (apply s3-main/with-init params))
(defmethod with-init s3-mock/implementation [& params] (apply s3-mock/with-init params))

(defmethod get-snapshot s3-main/implementation [& params] (apply s3-main/get-snapshot params))
(defmethod get-snapshot s3-mock/implementation [& params] (apply s3-mock/get-snapshot params))

(defmethod update-snapshot s3-main/implementation [& params] (apply s3-main/update-snapshot params))
(defmethod update-snapshot s3-mock/implementation [& params] (apply s3-mock/update-snapshot params))

(defmethod with-init s3-main/implementation [& params] (apply s3-main/with-init params))
(defmethod with-init s3-mock/implementation [& params] (apply s3-mock/with-init params))

(defn get-service-bucket-name
  [& [{:keys [aggregate-store-bucket]
       :or   {aggregate-store-bucket "aggregate-store"}}]]
  (str (util/get-env "AccountId")
       "-"
       (util/get-env "EnvironmentNameLower")
       "-"
       aggregate-store-bucket))

(def S3ConfigSchema
  (m/schema
   [:map
    [:storage
     [:map
      [:s3
       [:map
        [:bucket
         [:map
          [:name string?]]]]]]]
    [:category-fn {:description "Function that returns category for aggregate.
                              It is used later for searching. Max aggregates
                              in category is 1000"} fn?]]))
(defn register
  [ctx & {:keys [config
                 implementation]}]
  (common/register-fn
   ctx
   :config-schema S3ConfigSchema
   :config config
   :default-config {:storage     {:s3 {:bucket {:name (get-service-bucket-name config)}}}
                    :category-fn (fn [aggregate]
                                   (:id aggregate))}
   :available-implementations {:main s3-main/implementation
                               :mock s3-mock/implementation}
   :default-implementation :main
   :implementation implementation))

