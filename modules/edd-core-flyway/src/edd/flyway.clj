(ns edd.flyway
  "Database migrations at startup, for services that own their database (a
   monolith on Undertow). Components declare the migrations they need on ctx
   (edd.ctx/put-migration): the Postgres event store, the Postgres view
   store, the Postgres distributor. This feature runs every declared set
   with Flyway when the runtime starts, for every realm of edd.ctx/realms.
   Services whose database is migrated by their deployment do not register
   it."
  (:require [clojure.tools.logging :as log]
            [edd.core :as edd]
            [edd.ctx :as edd-ctx]
            [edd.db :as db])
  (:import [org.flywaydb.core Flyway]))

(set! *warn-on-reflection* true)

(def feature-id
  :edd/flyway)

(defn- jdbc-url
  [{:keys [host port dbname]}]
  (str "jdbc:postgresql://" host ":" port "/" dbname))

(defn- migrate-schema!
  "Migrates on a connection of Flyway's own, with the search path of the
   database: migrations use extensions installed in public."
  [{:keys [username password] :as database} schema location placeholders]
  (let [configuration
        (Flyway/configure)]
    (.dataSource configuration ^String (jdbc-url database) ^String username ^String password)
    (.schemas configuration (into-array String [schema]))
    (.locations configuration ^"[Ljava.lang.String;" (into-array String [location]))
    (.placeholders configuration ^java.util.Map placeholders)
    (let [result
          (.migrate (.load configuration))]
      (log/info "Migrated" {:schema schema
                            :location location
                            :applied (.migrationsExecuted result)}))))

(defn migrate!
  "Runs the migrations declared on ctx for every realm. placeholders
   override those a migration declares."
  [ctx placeholders]
  (let [database
        (db/init ctx)]
    (doseq [realm (edd-ctx/realms ctx)
            {:keys [location schema] :as migration} (edd-ctx/get-migrations ctx)]
      (migrate-schema! database
                       (schema ctx realm)
                       location
                       (merge {} (:placeholders migration) placeholders)))))

(defn register
  "Registers the migrations as a startup step. options: :placeholders, a
   map of Flyway placeholders, e.g. {\"historyPartitionCount\" \"4\"}."
  ([ctx]
   (register ctx {}))
  ([ctx {:keys [placeholders]}]
   (edd/reg-feature ctx feature-id
                    :init (fn [ctx]
                            (migrate! ctx placeholders)
                            ctx))))
