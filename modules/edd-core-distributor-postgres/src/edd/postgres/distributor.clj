(ns edd.postgres.distributor
  "Transport for a single service on the Postgres event store: no router, no
   queues. The event store writes an outbox marker
   (edd.postgres.distributor.outbox) in the transaction of every command with
   events or effects; one poller per realm claims markers and hands the
   service its own requests, an apply per aggregate and the commands of every
   effect, rebuilt from the stored response. Effects for another service are
   rejected when the command is stored. The outbox is a schema of its own
   per realm, declared as a migration (edd.ctx/put-migration) that
   edd-core-flyway or the deployment applies. Realms are those of
   edd.ctx/realms. Needs a long-running runtime (Undertow)."
  (:require [clojure.string :as string]
            [edd.ctx :as edd-ctx]
            [edd.dal :as dal]
            [edd.db :as db]
            [edd.distribution :as distribution]
            [edd.distribution.response :as response]
            [edd.postgres.distributor.outbox :as outbox]
            [edd.postgres.pool :as pool]
            [lambda.util :as util])
  (:import [java.util.concurrent Semaphore TimeUnit]))

(set! *warn-on-reflection* true)

(def feature-id
  :edd/postgres-distributor)

(def ^:private destination
  "postgres-distributor")

(def ^:private defaults
  {:poll-interval-ms 5000
   :batch-size 10
   :lease-seconds 60
   :max-attempts 10})

(def ^:private max-backoff-seconds
  300)

(defn source
  "Source the poller of realm consumes."
  [realm]
  (str destination "." (name realm)))

(defn- source-realm
  [source]
  (keyword (subs source (inc (count destination)))))

(defn- realm-ctx
  [ctx source]
  (assoc ctx :meta {:realm (source-realm source)}))

(defn- datasource
  [ctx]
  (pool/create-pool-mem (db/init ctx)))

(defn- signal!
  "The wake signal of a source, created when it is first asked for."
  ^Semaphore [signals source]
  (get (swap! signals update source #(or % (Semaphore. 0))) source))

(defn- wake!
  [signals]
  (doseq [^Semaphore signal (vals @signals)]
    (when (zero? (.availablePermits signal))
      (.release signal))))

(defn- publish-filter
  "The marker is committed with the command, publishing only wakes the
   pollers."
  [signals]
  (fn [ctx {:keys [messages] target :destination} _chain]
    (when-not (= destination target)
      (throw (ex-info (str "The Postgres distributor delivers to " (:service-name ctx) " only")
                      {:destination target})))
    (wake! signals)
    (mapv (fn [{:keys [id]}]
            {:id id
             :success true})
          messages)))

(defn requests
  "edd requests delivering a marker to the own service: an apply for every
   aggregate of the events, then the effects in stored order."
  [ctx {:keys [request-id interaction-id breadcrumbs]}]
  (let [response-ctx
        (assoc ctx
               :request-id request-id
               :interaction-id interaction-id
               :breadcrumbs breadcrumbs)

        summary
        (:data (dal/get-command-response response-ctx))

        {:keys [events effects]}
        (response/stored-response response-ctx summary)

        applies
        (map (fn [{:keys [id meta]}]
               {:request-id request-id
                :interaction-id interaction-id
                :meta meta
                :apply {:aggregate-id id
                        :service (:service-name ctx)}})
             events)]
    (vec (concat applies effects))))

(defn- describe
  [request]
  (if-let [apply-request (:apply request)]
    (str "apply " (:aggregate-id apply-request))
    (str "commands " (:breadcrumbs request))))

(defn- failure
  "The request of the delivery that failed: requests are handled in order,
   so it is the first one not acknowledged."
  [{:keys [marker-requests acked]}]
  (str "Delivery failed: " (describe (nth marker-requests acked))))

(defn- ->message
  [{:keys [request-id breadcrumbs]} delivery index request]
  {:record {:eventSource "edd:postgres-distributor"
            :messageId (str request-id ":" (string/join ":" breadcrumbs) ":" index)
            :body (util/to-json request)}
   :delivery delivery})

(defn- messages
  [{:keys [ctx]} marker]
  (let [marker-requests
        (requests ctx marker)

        delivery
        (atom {:marker marker
               :marker-requests marker-requests
               :acked 0
               :retried? false
               :leased-at (System/nanoTime)})]
    (when (empty? marker-requests)
      (outbox/delivered! ctx marker))
    (vec (map-indexed (partial ->message marker delivery) marker-requests))))

(defn- held-marker!
  "Next claimed marker this poller still holds, claiming due ones when it
   has none left. A marker waiting behind others is leased again when its
   turn comes; one that somebody else took meanwhile is skipped."
  [{:keys [ctx claimed] {:keys [batch-size lease-seconds]} :options}]
  (when (empty? @claimed)
    (reset! claimed (outbox/claim! ctx batch-size lease-seconds)))
  (loop []
    (when-let [marker (first @claimed)]
      (swap! claimed subvec 1)
      (if (outbox/renew! ctx marker lease-seconds)
        marker
        (recur)))))

(defn- await-signal
  [{:keys [^Semaphore signal] {:keys [poll-interval-ms]} :options}]
  (.tryAcquire signal (long poll-interval-ms) TimeUnit/MILLISECONDS)
  (.drainPermits signal))

(defn- poll
  "Requests of one marker, so a failure never touches another marker."
  [{:keys [datasource] :as poller}]
  (binding [pool/*DB* datasource]
    (if-let [marker (held-marker! poller)]
      (messages poller marker)
      (do
        (await-signal poller)
        []))))

(defn- lease-half-over?
  [{:keys [leased-at]} lease-seconds]
  (> (- (System/nanoTime) (long leased-at))
     (* 500000000 (long lease-seconds))))

(defn- ack
  [{:keys [ctx datasource] {:keys [lease-seconds]} :options} {:keys [delivery]}]
  (let [{:keys [marker marker-requests acked] :as delivered}
        (swap! delivery update :acked inc)]
    (binding [pool/*DB* datasource]
      (cond
        (= acked (count marker-requests))
        (outbox/delivered! ctx marker)

        (lease-half-over? delivered lease-seconds)
        (do
          (outbox/renew! ctx marker lease-seconds)
          (swap! delivery assoc :leased-at (System/nanoTime)))))))

(defn- backoff-seconds
  [attempts]
  (min (long max-backoff-seconds)
       (bit-shift-left 1 (min (long attempts) 20))))

(defn- retry
  "Called for the failed request and every request after it; the first
   call schedules the next attempt of the marker, or gives it up after
   :max-attempts. The transport has no dead letters of its own, so the
   consumer loop leaves that count to the outbox."
  [{:keys [ctx datasource] {:keys [max-attempts]} :options} {:keys [delivery]}]
  (let [[{:keys [marker retried?] :as failed} _]
        (swap-vals! delivery assoc :retried? true)

        attempts
        (long (:attempts marker))]
    (when-not retried?
      (binding [pool/*DB* datasource]
        (if (>= attempts (long max-attempts))
          (outbox/fail! ctx marker (failure failed))
          (outbox/retry! ctx marker (backoff-seconds attempts) (failure failed)))))))

(defn- consumer
  [{:keys [signals] :as options}]
  (fn [ctx source]
    (let [poller
          {:ctx (realm-ctx ctx source)
           :datasource (datasource ctx)
           :signal (signal! signals source)
           :options options
           :claimed (atom [])}]
      {:poll #(poll poller)
       :ack #(ack poller %)
       :retry #(retry poller %)
       :close (fn [])})))

(defn pending?
  "Whether events or effects of the service still wait for delivery in
   realm, for callers that read their own writes."
  [ctx realm]
  (let [pending-ctx
        (assoc ctx :meta {:realm realm})]
    (binding [pool/*DB* (datasource pending-ctx)]
      (outbox/pending? pending-ctx))))

(defn- assert-supported!
  [ctx]
  (when-not (= :postgres (:edd-event-store ctx))
    (throw (ex-info "The Postgres distributor needs the Postgres event store (edd.postgres.event-store/register)"
                    {:edd-event-store (:edd-event-store ctx)}))))

(defn- init
  "Polls the outbox of every realm unless the runtime was told what to
   consume."
  [ctx]
  (assert-supported! ctx)
  (update ctx :edd/consume #(or % (mapv source (edd-ctx/realms ctx)))))

(defn register
  "Registers the Postgres outbox as transport of a service that only talks
   to itself. options: :poll-interval-ms (5000) between polls without a wake
   signal, :batch-size (10) markers claimed at once, :lease-seconds (60) a
   claimed marker is kept from other pollers, :max-attempts (10) before a
   marker is given up (failed_at)."
  ([ctx]
   (register ctx {}))
  ([ctx options]
   (let [config
         (merge defaults options)

         signals
         (atom {})

         with-transport
         (distribution/register-transport ctx feature-id
                                          :init init
                                          :on-store outbox/add!
                                          :signals signals
                                          :publish-filter (publish-filter signals)
                                          :consumer (consumer (assoc config :signals signals))
                                          :response-destination (constantly destination)
                                          :service-destination (fn [_ctx _service stage _type]
                                                                 (source stage)))]
     (edd-ctx/put-migration with-transport outbox/migration))))
