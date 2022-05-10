(ns edd.view-store.elastic
  (:require [lambda.util :as util]
            [malli.core :as m]
            [edd.view-store.common :as common]
            [edd.view-store.common :refer [get-snapshot
                                           update-snapshot
                                           with-init]]
            [edd.view-store.impl.elastic.mock :as elastic-mock]
            [edd.view-store.impl.elastic.main :as elastic-main]))

(def realm elastic-main/realm)

(defmethod with-init elastic-main/implementation [& params] (apply elastic-main/with-init params))
(defmethod with-init elastic-mock/implementation [& params] (apply elastic-mock/with-init params))

(defmethod get-snapshot elastic-main/implementation [& params] (apply elastic-main/get-snapshot params))
(defmethod get-snapshot elastic-mock/implementation [& params] (apply elastic-mock/get-snapshot params))

(defmethod update-snapshot elastic-main/implementation [& params] (apply elastic-main/update-snapshot params))
(defmethod update-snapshot elastic-mock/implementation [& params] (apply elastic-mock/update-snapshot params))

(defmulti advanced-search
  (fn [ctx _query]
    (common/get-type ctx)))

(defmethod advanced-search elastic-main/implementation [& params] (apply elastic-main/advanced-search params))
(defmethod advanced-search elastic-mock/implementation [& params] (apply elastic-mock/advanced-search params))

(defmulti simple-search
  (fn [ctx _query]
    (common/get-type ctx)))

(defmethod simple-search elastic-main/implementation [& params] (apply elastic-main/simple-search params))
(defmethod simple-search elastic-mock/implementation [& params] (apply elastic-mock/simple-search params))

(def ElasticConfigSchema
  (m/schema
   [:map
    [:scheme string?]
    [:url string?]]))

(def default-endpoint "127.0.0.1:9200")

(defn register
  [ctx & {:keys [config
                 implementation]}]
  (common/register-fn
   ctx
   :config-schema ElasticConfigSchema
   :config config
   :default-config {:scheme (util/get-env "IndexDomainScheme" "https")
                    :url    (util/get-env "IndexDomainEndpoint" default-endpoint)}
   :available-implementations {:main elastic-main/implementation
                               :mock elastic-mock/implementation}
   :default-implementation :main
   :implementation implementation))
