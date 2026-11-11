(ns edd.postgres.distributor.outbox
  "Markers of the Postgres distributor (edd.postgres.distributor): one row
   per command response that still has events or effects to deliver. They
   live in a schema of their own per realm, <realm>_distributor (migration).
   Every fn runs on edd.postgres.pool/*DB* in the realm of ctx."
  (:require [clojure.string :as string]
            [edd.postgres.pool :refer [*DB*]]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(defn- schema
  [realm]
  (str (string/replace (name realm) "-" "_") "_distributor"))

(def migration
  {:id :edd/postgres-distributor
   :location "classpath:edd/postgres/distributor/migrations"
   :schema (fn [_ctx realm]
             (schema realm))})

(defn- table
  [ctx]
  (str (schema (get-in ctx [:meta :realm] :no_realm)) ".outbox"))

(defn- breadcrumbs->str
  [breadcrumbs]
  (string/join ":" (or breadcrumbs [])))

(defn- str->breadcrumbs
  [breadcrumbs]
  (if (string/blank? breadcrumbs)
    []
    (mapv parse-long (string/split breadcrumbs #":"))))

(defn- remote-services
  [{:keys [service-name]} effects]
  (vec (distinct (remove #(= (keyword service-name) %)
                         (map #(keyword (:service %)) effects)))))

(defn- assert-own-service!
  [ctx effects]
  (let [remote
        (remote-services ctx effects)]
    (when (seq remote)
      (let [message
            (str "The Postgres distributor delivers to " (:service-name ctx)
                 " only, effects for " remote " need SQS or Kafka and the router")]
        (throw (ex-info message
                        {:message message
                         :services remote}))))))

(defn add!
  "Inserts the marker of a response with events or effects, in the
   transaction storing it. Throws on an effect for another service."
  [{:keys [service-name request-id interaction-id breadcrumbs] :as ctx} {:keys [events effects]}]
  (assert-own-service! ctx effects)
  (when (or (seq events) (seq effects))
    (jdbc/execute! *DB*
                   [(str "INSERT INTO " (table ctx) "
                            (service_name, request_id, breadcrumbs, interaction_id)
                          VALUES (?,?,?,?)")
                    (name service-name)
                    request-id
                    (breadcrumbs->str breadcrumbs)
                    interaction-id])))

(defn- ->marker
  [{:keys [request_id breadcrumbs interaction_id attempts created_at last_error failed_at]}]
  {:request-id request_id
   :breadcrumbs (str->breadcrumbs breadcrumbs)
   :interaction-id interaction_id
   :attempts attempts
   :created-at created_at
   :last-error last_error
   :failed-at failed_at})

(defn- marker-key
  [{:keys [service-name]} {:keys [request-id breadcrumbs]}]
  [(name service-name) request-id (breadcrumbs->str breadcrumbs)])

(defn claim!
  "Leases up to limit due markers, oldest first, for lease-seconds: other
   pollers skip them until the lease runs out."
  [{:keys [service-name] :as ctx} limit lease-seconds]
  (let [claimed
        (jdbc/execute! *DB*
                       [(str "WITH due AS MATERIALIZED
                                     (SELECT request_id, breadcrumbs
                                        FROM " (table ctx) "
                                       WHERE service_name = ?
                                         AND next_attempt_at <= now()
                                         AND failed_at IS NULL
                                       ORDER BY created_at
                                       LIMIT ?
                                         FOR UPDATE SKIP LOCKED)
                              UPDATE " (table ctx) " AS outbox
                                 SET attempts = attempts + 1,
                                     next_attempt_at = now() + (? * interval '1 second')
                                FROM due
                               WHERE outbox.service_name = ?
                                 AND outbox.request_id = due.request_id
                                 AND outbox.breadcrumbs = due.breadcrumbs
                           RETURNING outbox.request_id, outbox.breadcrumbs, outbox.interaction_id,
                                     outbox.attempts, outbox.created_at")
                        (name service-name)
                        limit
                        lease-seconds
                        (name service-name)]
                       {:builder-fn rs/as-unqualified-lower-maps})]
    (mapv ->marker (sort-by :created_at claimed))))

(defn- update-claimed!
  "Runs the statement on the marker as long as this poller still holds it:
   nobody claimed it since (attempts) and it is not failed. Returns whether
   it did."
  [ctx marker statement & params]
  (let [updated
        (jdbc/execute-one! *DB*
                           (vec (concat [(str statement "
                                               WHERE service_name = ?
                                                 AND request_id = ?
                                                 AND breadcrumbs = ?
                                                 AND attempts = ?
                                                 AND failed_at IS NULL")]
                                        params
                                        (marker-key ctx marker)
                                        [(:attempts marker)])))]
    (pos? (long (:next.jdbc/update-count updated 0)))))

(defn renew!
  "Extends the lease of a claimed marker; false when it is no longer held."
  [ctx marker lease-seconds]
  (update-claimed! ctx marker
                   (str "UPDATE " (table ctx) "
                            SET next_attempt_at = now() + (? * interval '1 second')")
                   lease-seconds))

(defn delivered!
  [ctx marker]
  (update-claimed! ctx marker (str "DELETE FROM " (table ctx))))

(defn retry!
  "Schedules the next attempt of a claimed marker in delay-seconds."
  [ctx marker delay-seconds error]
  (update-claimed! ctx marker
                   (str "UPDATE " (table ctx) "
                            SET next_attempt_at = now() + (? * interval '1 second'),
                                last_error = ?")
                   delay-seconds
                   error))

(defn fail!
  "Takes a claimed marker out of polling, kept for inspection."
  [ctx marker error]
  (update-claimed! ctx marker
                   (str "UPDATE " (table ctx) "
                            SET failed_at = now(),
                                last_error = ?")
                   error))

(defn pending?
  "Whether a marker of the service still waits for delivery."
  [{:keys [service-name] :as ctx}]
  (let [found
        (jdbc/execute-one! *DB*
                           [(str "SELECT EXISTS (SELECT 1 FROM " (table ctx) "
                                                  WHERE service_name = ?
                                                    AND failed_at IS NULL) AS pending")
                            (name service-name)]
                           {:builder-fn rs/as-unqualified-lower-maps})]
    (boolean (:pending found))))

(defn markers
  "Every marker of the service, oldest first."
  [{:keys [service-name] :as ctx}]
  (mapv ->marker
        (jdbc/execute! *DB*
                       [(str "SELECT * FROM " (table ctx) "
                               WHERE service_name = ?
                               ORDER BY created_at")
                        (name service-name)]
                       {:builder-fn rs/as-unqualified-lower-maps})))
