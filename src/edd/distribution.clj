(ns edd.distribution
  "How a service hands its responses to the router and receives commands
   and events. The transport is a feature (see register-transport): memory
   ships with edd-core (edd.distribution.memory), SQS and Kafka are modules.
   Other features can wrap publishing with their own :publish-filter.

   Topology is the same for every transport: responses go to the router
   destination, the router (edd.distribution.router) fans effects out to the
   target service's commands destination and events to its events
   destination; the transport names them."
  (:require [clojure.tools.logging :as log]
            [edd.ctx :as edd-ctx]
            [lambda.request :as request]))

(defn transport
  "The registered transport feature: the feature providing :consumer."
  [ctx]
  (last (filter :consumer (edd-ctx/get-features ctx))))

(defn register-transport
  "Registers the transport feature feature-id, replacing a transport
   registered before (edd.test.fixture.dal/ctx registers the memory one).
   options:
     :publish-filter   (fn [ctx {:keys [destination messages]} chain]) sends
                       messages {:id :body :group-id :deduplication-id},
                       returns [{:id :success}]; innermost link, does not
                       call chain
     :consumer         (fn [ctx source]) opened on the polling thread, returns
                       {:poll (fn []) :ack (fn [message]) :retry (fn [message])
                        :close (fn [])}; :poll returns [{:record record}]
     :max-message-size largest message body in bytes (default 1 MiB)
     :ack-delivered    (fn [ctx records]) acknowledges the records of a failed
                       batch that succeeded (Lambda SQS triggers)
     :dead-letter      (fn [ctx source message]) moves a message that kept
                       failing out of source
     :unwrap           (fn [ctx body]) resolves a body the transport replaced
                       by a reference when it was too big
     :response-destination (fn [ctx]) and
     :service-destination  (fn [ctx service stage type]) name destinations;
                       type is :commands or :events"
  [ctx feature-id & {:as options}]
  (let [without-transport
        (assoc-in ctx [:edd-core :features] (vec (remove :consumer (edd-ctx/get-features ctx))))]
    (edd-ctx/put-feature without-transport (assoc options
                                                  :feature-id feature-id
                                                  :deps {}))))

(defn validate!
  "edd.core/handler calls this for every request so a missing transport
   fails before anything is stored."
  [ctx]
  (when-not (transport ctx)
    (let [message
          (str "No distributor registered for " (:service-name ctx)
               "; register one, e.g. (edd.sqs.distributor/register ctx)")]
      (throw (ex-info message
                      {:message message
                       :service-name (:service-name ctx)}))))
  ctx)

(defn- no-transport
  [ctx _request]
  (validate! ctx)
  [])

(defn publish!
  "Publishes messages to destination through the :publish-filter chain of
   the registered features, the transport innermost. Returns [{:id :success}]."
  [ctx destination messages]
  (let [{transport-filter :publish-filter}
        (transport ctx)

        terminal
        (if transport-filter
          (fn [ctx request]
            (transport-filter ctx request no-transport))
          no-transport)

        wrappers
        (keep :publish-filter (remove :consumer (edd-ctx/get-features ctx)))

        chain
        (reduce (fn [next-link publish-filter]
                  (fn [ctx request]
                    (publish-filter ctx request next-link)))
                terminal
                (reverse wrappers))]
    (chain ctx {:destination destination
                :messages messages})))

(def ^:const default-max-message-size
  1048576)

(defn max-message-size
  "Largest message body in bytes the transport accepts."
  ^long [ctx]
  (long (get (transport ctx) :max-message-size default-max-message-size)))

(defn dead-letters?
  [ctx]
  (some? (:dead-letter (transport ctx))))

(defn dead-letter!
  [ctx source message]
  ((:dead-letter (transport ctx)) ctx source message))

(defn- open
  [ctx source]
  ((:consumer (transport ctx)) ctx source))

(defn current-ctx
  "ctx with fresh credentials when the runtime provides ::ctx-fn."
  [ctx]
  (if-let [ctx-fn (::ctx-fn ctx)]
    (ctx-fn)
    ctx))

(defn stage
  [{:keys [realm stage]}]
  (or stage
      (case realm
        :test "test"
        "prod")))

(defn- default-response-destination
  [_ctx]
  "router")

(defn- default-service-destination
  [_ctx service stage type]
  (str (name service) "." stage "." (name type)))

(defn response-destination
  "Where services send responses for the router; named by the transport."
  [ctx]
  ((get (transport ctx) :response-destination default-response-destination) ctx))

(defn service-destination
  "Commands or events (type) destination of service for stage; named by the
   transport."
  [ctx service stage type]
  ((get (transport ctx) :service-destination default-service-destination) ctx service stage type))

(defn service-sources
  "Command and event destinations of the current service for every stage."
  [{:keys [service-name] :as ctx}]
  (for [stage ["prod" "test"]
        type [:commands :events]]
    (service-destination ctx service-name stage type)))

(defn consume-sources
  "Sources a long-running runtime polls: :edd/consume (empty disables), the
   router response destination for a router (:edd/router), else the service
   sources."
  [ctx]
  (let [configured
        (:edd/consume ctx)]
    (cond
      (nil? (transport ctx)) []
      (some? configured) (vec configured)
      (:edd/router ctx) [(response-destination ctx)]
      :else (vec (service-sources ctx)))))

(defn byte-size
  "UTF-8 size of s in bytes."
  ^long [^String s]
  (alength (.getBytes (str s) "UTF-8")))

(defn failed-ids
  "Ids of messages without an explicit successful result; a message the
   distributor did not report on counts as failed."
  [messages results]
  (let [succeeded
        (set (map :id (filter :success results)))]
    (set (remove succeeded (map :id messages)))))

(defn distribute!
  "Publishes messages through the registered distributor and throws when
   any of them failed."
  [ctx destination messages]
  (let [results
        (publish! ctx destination messages)

        failed
        (failed-ids messages results)]
    (when (seq failed)
      (throw (ex-info "Distribution failed" {:destination destination
                                             :failed (vec failed)})))
    results))

(defn distribute-response!
  "Publishes the router payloads the current request queued (see
   edd.distribution.response) to the router destination, one per message."
  [ctx]
  (let [payloads
        (get @request/*request* :router-payloads)]
    (when (seq payloads)
      (let [destination
            (response-destination ctx)]
        (log/info "Distributing response" {:destination destination
                                           :messages (count payloads)})
        (vec (mapcat (fn [index payload]
                       (distribute! ctx destination [{:id (str index)
                                                      :body (str "{\"Records\":[" payload "]}")}]))
                     (range)
                     payloads))))))

(defn unwrap
  "Message body as sent, for transports that replace big bodies with a
   reference (:unwrap of the transport feature)."
  [ctx body]
  (if-let [unwrap-body (:unwrap (transport ctx))]
    (unwrap-body ctx body)
    body))

(defn acknowledge-delivered!
  [ctx records]
  (when-let [ack-delivered (and (seq records) (:ack-delivered (transport ctx)))]
    (ack-delivered ctx records)))

(defn- handled?
  [handle-record source record]
  (try
    (boolean (handle-record record))
    (catch Throwable e
      (log/error e "Handling record failed" {:source source})
      false)))

(defn- message-key
  [{:keys [record]}]
  (or (:messageId record)
      [(:topic record) (:partition record) (:offset record)]))

(defn- move-to-dead-letters!
  [ctx source-consumer source message tries]
  (try
    (log/error "Moving message to dead letters" {:source source :attempts tries})
    (dead-letter! (current-ctx ctx) source message)
    ((:ack source-consumer) message)
    true
    (catch Throwable e
      (log/error e "Moving message to dead letters failed, retrying it" {:source source})
      false)))

(defn- dead-letter?
  "Counts attempts on transports with dead letters (others redrive on their
   own) and moves the message there after max-attempts failures. A failed
   move leaves the message to be retried."
  [ctx source-consumer source message attempts max-attempts]
  (when (and max-attempts
             (dead-letters? ctx))
    (let [message-id
          (message-key message)

          tries
          (get (swap! attempts update message-id (fnil inc 0)) message-id)]
      (when (and (>= (long tries) (long max-attempts))
                 (move-to-dead-letters! ctx source-consumer source message tries))
        (swap! attempts dissoc message-id)
        true))))

(defn- process-batch!
  "Handles messages in order. The first failure ends the batch: it and every
   message after it are retried, newest first, so a transport rewinding per
   partition (Kafka) ends at the earliest unprocessed offset of each."
  [{:keys [ctx source-consumer source handle-record retry-delay-ms attempts max-attempts]} messages]
  (loop [[message & more :as remaining] messages]
    (when message
      (cond
        (handled? handle-record source (:record message))
        (do
          (swap! attempts dissoc (message-key message))
          ((:ack source-consumer) message)
          (recur more))

        (dead-letter? ctx source-consumer source message attempts max-attempts)
        (recur more)

        :else
        (do
          (doseq [unprocessed (reverse remaining)]
            ((:retry source-consumer) unprocessed))
          (Thread/sleep (long retry-delay-ms)))))))

(defn- open-consumer
  "Creates the consumer, retrying until it succeeds or the loop stops."
  [ctx source running retry-delay-ms]
  (loop []
    (when @running
      (let [created
            (try
              (open ctx source)
              (catch Throwable e
                (log/error e "Creating consumer failed" {:source source})
                (Thread/sleep (long retry-delay-ms))
                nil))]
        (or created (recur))))))

(defn- consume-loop
  [ctx source handle-record running retry-delay-ms max-attempts]
  (when-let [source-consumer (open-consumer ctx source running retry-delay-ms)]
    (try
      (let [batch-ctx
            {:ctx ctx
             :source-consumer source-consumer
             :source source
             :handle-record handle-record
             :retry-delay-ms retry-delay-ms
             :attempts (atom {})
             :max-attempts max-attempts}]
        (while @running
          (try
            (process-batch! batch-ctx ((:poll source-consumer)))
            (catch InterruptedException _
              (reset! running false))
            (catch Throwable e
              (log/error e "Polling failed" {:source source})
              (Thread/sleep (long retry-delay-ms))))))
      (finally
        ((:close source-consumer))))))

(defn start-consumer!
  "Polls source on its own thread and calls (handle-record record) for every
   message: truthy acks it, falsy or a throw retries it after retry-delay-ms.
   After max-attempts failures a message goes to dead letters when the
   distributor supports them. Returns a stop function."
  [ctx source handle-record & {:keys [retry-delay-ms max-attempts]
                               :or {retry-delay-ms 1000
                                    max-attempts 10}}]
  (let [running
        (atom true)

        worker
        (Thread. ^Runnable (bound-fn []
                             (consume-loop ctx source handle-record running retry-delay-ms max-attempts))
                 (str "edd-consumer-" source))]
    (.start worker)
    (log/info "Consuming" {:source source})
    (fn stop []
      (reset! running false)
      (.join worker 30000))))
