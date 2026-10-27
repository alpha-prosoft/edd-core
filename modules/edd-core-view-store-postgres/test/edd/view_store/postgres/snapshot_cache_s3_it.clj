(ns edd.view-store.postgres.snapshot-cache-s3-it
  "The Postgres view store with the S3 snapshot cache
   (edd.s3.view-store/register-snapshot-cache) against real S3: the
   {AccountId}-{EnvironmentNameLower}-aggregates bucket and AWS credentials
   from the environment, as set up by pre-build.sh."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [edd.ctx :as edd-ctx]
            [edd.postgres.event-store :as event-store]
            [edd.s3.view-store :as s3-view-store]
            [edd.search :as search]
            [edd.view-store.postgres.fixtures :as fix]
            [edd.view-store.postgres.view-store :as view-store]
            [lambda.ctx :as lambda-ctx]
            [lambda.uuid :as uuid]))

(use-fixtures :once
  fix/fix-with-db
  fix/fix-with-db-init)

(use-fixtures :each
  fix/fix-truncate-db)

(defn- service-ctx
  []
  (let [ctx
        (assoc (lambda-ctx/init {})
               :service-name :test-service
               :meta {:realm :test})

        with-stores
        (event-store/register (view-store/register ctx))]
    (edd-ctx/init-features (s3-view-store/register-snapshot-cache with-stores))))

(deftest snapshots-are-kept-in-s3-test
  (let [ctx
        (service-ctx)

        id
        (uuid/gen)

        v1
        {:id id :version 1 :name "one"}

        v2
        {:id id :version 2 :name "two"}

        name-and-version
        (fn [aggregate]
          (select-keys aggregate [:name :version]))]
    (search/update-aggregate ctx v1)
    (search/update-aggregate ctx v2)

    (testing "every update is written to S3, latest and by version"
      (is
       (= {:name "two" :version 2}
          (name-and-version (s3-view-store/get-from-s3 ctx id))))

      (is
       (= {:name "one" :version 1}
          (name-and-version (s3-view-store/get-history-from-s3 ctx id 1)))))

    (testing "with Postgres emptied the snapshots still come back, from S3"
      (fix/fix-truncate-db (fn []))

      (is
       (= {:name "two" :version 2}
          (name-and-version (search/get-snapshot ctx id))))

      (is
       (= {:name "one" :version 1}
          (name-and-version (search/get-snapshot ctx {:id id :version 1})))))

    (testing "an aggregate S3 does not have is read from Postgres"
      (is
       (nil?
        (search/get-snapshot ctx (uuid/gen)))))))
