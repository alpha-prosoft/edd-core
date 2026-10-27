(ns edd.distribution.router
  "Router fan-out, independent of transport: sends every effect of a
   response to the target service's commands destination and every event to
   the events destination, through the registered transport."
  (:require [clojure.tools.logging :as log]
            [edd.distribution :as distribution]
            [lambda.util :as util]
            [lambda.uuid :as uuid]
            [edd.distribution.response :as response]
            [edd.response.cache :as cache])
  (:import [java.util.concurrent Callable ExecutorService Executors Future ThreadFactory]))

(def ^:private max-publish-threads
  100)

(defonce ^:private publish-pool
  (delay
   (Executors/newFixedThreadPool
    (int max-publish-threads)
    (reify ThreadFactory
      (newThread [_ runnable]
        (doto (Thread. ^Runnable runnable "edd-router-publish")
          (.setDaemon true)))))))

(def ^:private publish-chunk-size
  10)

(defn effect-messages
  [ctx effects start]
  (map-indexed
   (fn [index {:keys [service meta] :as cmd}]
     {:id (str (+ (long index) (long start)))
      :destination (distribution/service-destination ctx service (distribution/stage meta) :commands)
      :body (util/to-json cmd)
      :group-id (or (:group-id meta)
                    (get-in cmd [:commands 0 :id]))
      :source cmd
      :deduplication-id (str (uuid/gen))})
   effects))

(defn event-messages
  [ctx service events start]
  (map-indexed
   (fn [index {:keys [id meta] :as event}]
     {:id (str (+ (long index) (long start)))
      :destination (distribution/service-destination ctx service (distribution/stage meta) :events)
      :body (util/to-json {:apply {:aggregate-id id
                                   :service service}
                           :meta meta
                           :request-id (:request-id event)
                           :interaction-id (:interaction-id event)})
      :source event
      :group-id id
      :deduplication-id (str (uuid/gen))})
   events))

(defn- publish-destination
  [ctx destination messages]
  (try
    (distribution/publish! ctx
                           destination
                           (mapv #(dissoc % :destination :source) messages))
    (catch Exception e
      (log/error e "Publishing failed" {:destination destination})
      (mapv (fn [{:keys [id]}]
              {:id id
               :success false})
            messages))))

(def ^:private lanes-per-destination
  100)

(defn- publish-lane
  "Messages of one lane, published batch after batch so every group id keeps
   its order (an SQS FIFO group, a Kafka key). After a failed batch the rest
   of the lane is not sent and counts as failed, so nothing overtakes it."
  [ctx destination lane]
  (loop [[chunk & more] (partition-all publish-chunk-size lane)
         results []]
    (if-not chunk
      results
      (let [chunk-results
            (publish-destination ctx destination chunk)

            all-results
            (into results chunk-results)]
        (if (seq (distribution/failed-ids chunk chunk-results))
          (into all-results (map #(hash-map :id (:id %) :success false) (apply concat more)))
          (recur more all-results))))))

(defn- lane-of
  [{:keys [destination group-id]}]
  [destination (mod (hash group-id) (long lanes-per-destination))])

(defn publish-all!
  "Publishes messages in up to 100 lanes per destination. A group id always
   maps to the same lane, so its messages stay in order while batches of 10
   and lanes run in parallel on a shared pool. Returns [{:id :success}]."
  [ctx messages]
  (let [lanes
        (group-by lane-of messages)]
    (if (empty? lanes)
      []
      (let [^ExecutorService pool
            @publish-pool

            tasks
            (mapv (fn [[[destination _lane] lane]]
                    ^Callable (bound-fn []
                                (publish-lane ctx destination lane)))
                  lanes)]
        (vec (mapcat #(.get ^Future %) (.invokeAll pool tasks)))))))

(defn- undelivered
  [messages results]
  (let [failed
        (distribution/failed-ids messages results)]
    (map :source (filter #(contains? failed (:id %)) messages))))

(defn route
  "Fans the response out and returns what could not be delivered,
   {:effects [...] :events [...]}, empty when everything was delivered."
  [ctx {:keys [service] :as content}]
  (let [resp
        (get content :resp content)

        effects
        (vec (effect-messages ctx (or (:commands resp) (:effects resp)) 0))

        events
        (vec (event-messages ctx service (:events resp) (count effects)))

        _
        (log/info "Routing" {:service service
                             :effects (count effects)
                             :events (count events)})

        results
        (publish-all! ctx (into effects events))

        failed-effects
        (undelivered effects results)

        failed-events
        (undelivered events results)]
    (cond-> {}
      (seq failed-effects) (assoc :effects (vec failed-effects))
      (seq failed-events) (assoc :events (vec failed-events)))))

(def ^:private requeue-attempts
  3)

(defn- requeue-remaining!
  "What was not delivered goes back to the router as new messages, so a
   retry re-sends only those. Should requeueing fail as well the whole record
   is retried, which is safe: commands are deduplicated by request id and
   breadcrumbs, and re-applied events rebuild the same aggregate."
  [ctx service remaining]
  (log/warn "Requeueing undelivered messages" {:effects (count (:effects remaining))
                                               :events (count (:events remaining))})
  (let [messages
        (vec (map-indexed (fn [index payload]
                            {:id (str index)
                             :body (response/records-body payload)})
                          (response/payloads ctx service remaining)))]
    (loop [attempt 1]
      (let [result
            (try
              (distribution/distribute! ctx (distribution/response-destination ctx) messages)
              {:success true}
              (catch Exception e
                (if (< (long attempt) (long requeue-attempts))
                  (do
                    (log/warn e "Requeue failed, retrying" {:attempt attempt})
                    (Thread/sleep (* 200 (long attempt))))
                  (throw e))
                nil))]
        (or result (recur (inc (long attempt))))))))

(defn handle-record
  "Routes one response record {:service :resp {:events :effects}}."
  [ctx record]
  (let [{:keys [service] :as record}
        (distribution/unwrap ctx (cache/resolve-record ctx (if (contains? record :Records)
                                                             (first (:Records record))
                                                             record)))]
    (when-not (or (contains? record :resp) (contains? record :service))
      (throw (ex-info "Unrecognized router record" {:keys (keys record)})))
    (let [remaining
          (route ctx record)]
      (if (empty? remaining)
        {:success true}
        (requeue-remaining! ctx service remaining)))))

(defn handler
  "Router handler: routes one router message, the body of a record of the
   router response destination."
  [ctx body]
  (util/d-time
   "Routing request"
   (handle-record ctx body)))
