(ns edd.distribution.memory
  "In-memory transport: destinations, consumers, routing and dead letters,
   for tests. Messages live in the given broker atom, or in
   lambda.test.fixture.state/*dal-state* (as edd.test.fixture.dal registers
   it) so every with-mock-dal starts empty. *dal-state* is a dynamic binding:
   consumers from start-consumer! inherit it, other threads (an HTTP server)
   need an explicit broker atom."
  (:require [edd.distribution :as distribution]
            [edd.distribution.router :as router]
            [lambda.test.fixture.state :as state]
            [lambda.util :as util]))

(defn- broker-state
  [broker]
  (or broker state/*dal-state*))

(defn- waiting
  [broker destination]
  (get-in @(broker-state broker) [:distribution destination] []))

(defn- ->record
  [destination {:keys [id body group-id]}]
  {:eventSource "edd:memory"
   :destination destination
   :messageId id
   :group-id group-id
   :body body})

(defn- remove-message!
  [broker destination message]
  (swap! (broker-state broker)
         update-in [:distribution destination]
         (fn [messages]
           (let [[before after]
                 (split-with #(not (identical? message %)) messages)]
             (vec (concat before (rest after)))))))

(defn- publish-filter
  [broker]
  (fn [_ctx {:keys [destination messages]} _chain]
    (swap! (broker-state broker)
           update-in [:distribution destination]
           (fnil into [])
           messages)
    (mapv (fn [{:keys [id]}]
            {:id id
             :success true})
          messages)))

(defn- consumer
  [broker]
  (fn [_ctx source]
    {:poll (fn []
             (if-let [message (first (waiting broker source))]
               [{:record (->record source message)
                 :message message}]
               (do
                 (Thread/sleep 10)
                 [])))
     :ack (fn [{:keys [message]}]
            (remove-message! broker source message))
     :retry (fn [_message])
     :close (fn [])}))

(defn- dead-letter
  [ctx source {:keys [record]}]
  (distribution/publish! ctx (str source ".dlq") [{:id (:messageId record)
                                                   :body (:body record)}]))

(defn register
  "Registers the in-memory transport; without a broker atom it uses the
   test fixture state. Options: :max-message-size in bytes."
  ([ctx]
   (register ctx nil))
  ([ctx broker]
   (register ctx broker {}))
  ([ctx broker {:keys [max-message-size]
                :or {max-message-size distribution/default-max-message-size}}]
   (distribution/register-transport ctx :edd/memory-distribution
                                    :broker broker
                                    :max-message-size max-message-size
                                    :publish-filter (publish-filter broker)
                                    :consumer (consumer broker)
                                    :dead-letter dead-letter)))

(defn- broker-of
  [ctx]
  (:broker (distribution/transport ctx)))

(defn messages
  "Messages waiting in destination."
  [ctx destination]
  (waiting (broker-of ctx) destination))

(defn bodies
  "Parsed bodies of the messages waiting in destination."
  [ctx destination]
  (mapv #(util/to-edn (:body %)) (messages ctx destination)))

(defn published
  "Parsed bodies of every waiting message, by destination."
  [ctx]
  (into {}
        (map (fn [[destination waiting-messages]]
               [destination (mapv #(util/to-edn (:body %)) waiting-messages)]))
        (:distribution @(broker-state (broker-of ctx)))))

(defn drain!
  "Synchronously handles every message waiting in destination with
   (handle-record record), removing each after it was handled. Returns the
   handler results."
  [ctx destination handle-record]
  (loop [results []]
    (if-let [message (first (messages ctx destination))]
      (let [result
            (handle-record (->record destination message))]
        (remove-message! (broker-of ctx) destination message)
        (recur (conj results result)))
      results)))

(defn route!
  "Runs the router over the response destination, moving effects and events
   into the service destinations."
  [ctx]
  (drain! ctx
          (distribution/response-destination ctx)
          #(router/handle-record ctx (util/to-edn (:body %)))))
