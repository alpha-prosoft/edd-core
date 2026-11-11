(ns edd.view-store.postgres.migration-test
  (:require [clojure.test :refer [deftest is]]
            [edd.ctx :as edd-ctx]
            [edd.view-store.postgres.api :as api]
            [edd.view-store.postgres.view-store :as sut]
            [honey.sql :as sql]))

(deftest migrated-schema-is-the-one-queried-test
  (let [{:keys [schema]}
        (first (edd-ctx/get-migrations (sut/register {:service-name :my-svc})))]

    (is
     (= (str "SELECT * FROM " (schema {:service-name :my-svc} :main) ".aggregates")
        (first (sql/format {:select [:*]
                            :from [(api/->table :main :my-svc)]}))))))
