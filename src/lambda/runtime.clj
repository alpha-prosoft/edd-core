(ns lambda.runtime
  "Request pipeline shared by every runtime (AWS Lambda, Undertow, queue
   consumers). A runtime decodes its input into edd requests (lambda.http for
   HTTP, handle-records for queue records) and runs each through its filters
   and the handler.

   Filters are runtime options, (fn [ctx request chain]) wrapping the handler
   like servlet filters: call (chain ctx request) to proceed and get the
   response, or return a response without calling it. The first filter is
   the outermost."
  (:require [clojure.tools.logging :as log]
            [edd.ctx :as edd-ctx]
            [edd.distribution :as distribution]
            [edd.request-cache :as request-cache]
            [lambda.ctx :as lambda-ctx]
            [lambda.logging.state :as log-state]
            [lambda.request :as request]
            [lambda.util :as util]))

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)

(def ^:private migration-hint
  "see MIGRATIONS.md, \"Runtime filters\"")

(defn options
  "Validates runtime options, rejecting the removed :post-filter (responses
   are encoded by the runtime) and {:cond :fn} filters."
  [{:keys [filters] :as opts}]
  (when (contains? opts :post-filter)
    (throw (ex-info (str ":post-filter was removed, the runtime encodes responses itself and "
                         "filters post-process the response of (chain ctx request); " migration-hint)
                    {:option :post-filter})))
  (doseq [f filters]
    (when-not (fn? f)
      (throw (ex-info (str "Filters are (fn [ctx request chain]); lambda.filters/from-api, from-queue "
                           "and to-api are built into the runtimes; " migration-hint)
                      {:filter (if (map? f) (vec (keys f)) f)}))))
  opts)

(defn init
  [ctx handler opts]
  (assoc ctx
         :handler handler
         :filters (vec (:filters (options opts)))))

(defn start
  "ctx every runtime starts from: handler and filters, the service
   configuration and the initialized features."
  [ctx handler opts]
  (edd-ctx/init-features
   (lambda-ctx/init
    (init ctx handler opts))))

(defmacro with-invocation
  "Scope of one invocation: cache is what the runtime keeps between
   invocations, request-state the initial lambda.request/*request* ({:scoped
   true} caches deps and applies for the invocation)."
  [cache request-state & body]
  `(binding [util/*cache* ~cache
             request/*request* (atom ~request-state)
             log-state/*invocation-start-ns* (System/nanoTime)]
     ~@body))

(defn error-response
  [_ctx e]
  (log/error e "Error processing request")
  {:exception {:message (or (ex-data e)
                            (ex-message e)
                            "Unable to parse exception")}})

(defn handle-with
  "Calls f, turning exceptions into an error response."
  [ctx f]
  (try
    (f)
    (catch Exception e
      (error-response ctx e))
    (catch AssertionError e
      (error-response ctx e))))

(defn- chain
  [filters handler]
  (reduce
   (fn [next-chain f]
     (fn [ctx request]
       (f ctx request next-chain)))
   (fn [ctx request]
     (doall (handler ctx request)))
   (reverse filters)))

(defn handle
  "Runs one edd request through the filters and the handler."
  [{:keys [filters handler] :as ctx} request]
  (util/d-time
   "time-invoke-handler"
   (handle-with ctx #((chain filters handler) ctx request))))

(defn failed?
  [response]
  (some? (if (sequential? response)
           (some :exception response)
           (:exception response))))

(defn service-user
  "The service itself as the user of requests nobody stands behind."
  [ctx]
  {:id (name (:service-name ctx))
   :role :non-interactive})

(defn authenticate-records
  "Runtime filter filling in the user of queue records: effects and events
   arrive without a caller, so they run as the service's :non-interactive
   user whoever triggered the command they come from. options:
     :source        only records of this :eventSource (\"edd:kafka\",
                    \"aws:sqs\", ..), default every queue record
     :resolve-user  (fn [ctx request]) returning the user, default
                    service-user
   The user goes to the request [:meta :user]; HTTP requests and other
   events pass through."
  ([]
   (authenticate-records {}))
  ([{:keys [source resolve-user]
     :or {resolve-user (fn [ctx _request]
                         (service-user ctx))}}]
   (fn [ctx request chain]
     (let [{:keys [eventSource] :as record}
           (:record ctx)]
       (if (and record
                (or (nil? source) (= source eventSource)))
         (chain ctx (assoc-in request [:meta :user] (resolve-user ctx request)))
         (chain ctx request))))))

(defn handle-records
  "Handles queue records in order, each body as one request with the
   record in ctx :record, and stops at the first failure. Returns the
   responses of the handled records. Every record reads its aggregates from
   the stores, so a batch behaves like its records handled one by one."
  [ctx records]
  (loop [[record & more] records
         responses []]
    (if-not record
      responses
      (let [_
            (request-cache/clear)

            response
            (handle-with ctx #(handle (assoc ctx :record record)
                                      (distribution/unwrap ctx (util/to-edn (:body record)))))

            handled
            (conj responses response)]
        (if (failed? response)
          handled
          (recur more handled))))))

(defn distribute!
  "Distributes the router payloads of the request. Returns the response, or
   an error response when distribution failed: the request is then repeated
   and its stored response distributed again."
  [ctx response]
  (handle-with ctx (fn []
                     (distribution/distribute-response! ctx)
                     response)))

(defn complete-records!
  "Distributes the router payloads of the handled records. When one failed,
   the ones before it are acknowledged so only the rest is delivered again;
   when distribution failed nothing is acknowledged. Returns whether the
   records have to be delivered again."
  [ctx records responses]
  (cond
    (failed? (distribute! ctx nil))
    true

    (failed? (last responses))
    (do
      (distribution/acknowledge-delivered! ctx (take (dec (count responses)) records))
      true)

    :else
    false))
