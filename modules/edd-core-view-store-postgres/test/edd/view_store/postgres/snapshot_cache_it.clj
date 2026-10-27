(ns edd.view-store.postgres.snapshot-cache-it
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [edd.postgres.event-store :as event-store]
            [edd.search :as search]
            [edd.snapshot-cache :as snapshot-cache]
            [edd.view-store.postgres.fixtures :as fix]
            [edd.view-store.postgres.view-store :as view-store]
            [lambda.ctx :as lambda-ctx]
            [lambda.uuid :as uuid]))

(use-fixtures :once
  fix/fix-with-db
  fix/fix-with-db-init)

(use-fixtures :each
  fix/fix-truncate-db)

(defn- cached-ctx
  [cached]
  (let [ctx
        (assoc (lambda-ctx/init {})
               :service-name :test-service
               :meta {:realm :test})

        with-stores
        (event-store/register (view-store/register ctx))]
    (snapshot-cache/register with-stores
                             {:store         (fn [_ctx aggregate]
                                               (swap! cached assoc
                                                      [(:id aggregate)] aggregate
                                                      [(:id aggregate) (:version aggregate)] aggregate))
                              :fetch         (fn [_ctx id]
                                               (get @cached [id]))
                              :fetch-version (fn [_ctx id version]
                                               (get @cached [id version]))})))

(deftest snapshot-cache-test
  (let [cached
        (atom {})

        ctx
        (cached-ctx cached)

        id
        (uuid/gen)

        v1
        {:id id :version 1 :name "one"}

        v2
        {:id id :version 2 :name "two"}]
    (search/update-aggregate ctx v1)
    (search/update-aggregate ctx v2)

    (testing "every update is cached"
      (is
       (= v2
          (get @cached [id])))

      (is
       (= v1
          (get @cached [id 1]))))

    (testing "snapshots come from the cache first"
      (swap! cached assoc [id] (assoc v2 :name "cached") [id 1] (assoc v1 :name "cached"))

      (is
       (= "cached"
          (:name (search/get-snapshot ctx id))))

      (is
       (= "cached"
          (:name (search/get-snapshot ctx {:id id :version 1})))))

    (testing "a cache miss reads Postgres"
      (reset! cached {})

      (is
       (= "two"
          (:name (search/get-snapshot ctx id))))

      (is
       (= "one"
          (:name (search/get-snapshot ctx {:id id :version 1})))))))
