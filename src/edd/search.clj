(ns edd.search
  (:require [edd.view-store.elastic :as elastic-view-store]
            [clojure.tools.logging :as log]))

(defn advanced-search
  [{:keys [query] :as ctx} & [query-param]]
  (elastic-view-store/advanced-search ctx (or query-param
                                              query)))

(defn simple-search
  [{:keys [query] :as ctx} & [query-param]]
  (elastic-view-store/simple-search ctx (or query-param
                                            query)))

