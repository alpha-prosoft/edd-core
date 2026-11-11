(ns edd.flyway-it
  (:require [clojure.test :refer [deftest is]]
            [edd.ctx :as edd-ctx]
            [edd.db :as db]
            [edd.flyway :as sut]
            [edd.postgres.event-store :as event-store]
            [edd.postgres.pool :as pool]
            [edd.view-store.postgres.view-store :as view-store]
            [lambda.uuid :as uuid]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(defn- tables
  [ctx schema]
  (set (map :tablename
            (jdbc/execute! (pool/create-pool-mem (db/init ctx))
                           ["SELECT tablename FROM pg_tables WHERE schemaname = ?" schema]
                           {:builder-fn rs/as-unqualified-lower-maps}))))

(deftest declared-migrations-run-for-every-realm-at-startup-test
  (let [suffix
        (subs (str (uuid/gen)) 0 8)

        realm
        (keyword (str "fly_" suffix))

        ctx
        (sut/register
         (view-store/register
          (event-store/register {:service-name :flyway-svc
                                 :environment-name-lower "local"
                                 :hosted-zone-name "example.com"
                                 :realms [realm]})))

        _
        (edd-ctx/init-features ctx)]

    (is
     (contains? (tables ctx (name realm)) "command_request_log"))

    (is
     (contains? (tables ctx (str (name realm) "_flyway_svc")) "aggregates"))

    (is
     (some? (edd-ctx/init-features ctx)))))
