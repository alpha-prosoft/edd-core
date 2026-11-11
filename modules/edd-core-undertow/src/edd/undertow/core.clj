(ns edd.undertow.core
  (:require [lambda.runtime :as runtime]
            [lambda.http :as http]
            [clojure.tools.logging :as log]
            [edd.ctx :as edd-ctx]
            [edd.distribution :as distribution]
            [lambda.util :as util]
            [lambda.uuid :as uuid])
  (:import [io.undertow Undertow Undertow$Builder Undertow$ListenerInfo UndertowOptions]
           [io.undertow.server HttpHandler HttpServerExchange]
           [io.undertow.util HeaderMap HeaderValues HttpString]
           [java.net InetSocketAddress]
           [java.nio.charset StandardCharsets]
           [java.util Base64 Deque Map$Entry]
           [org.xnio OptionMap Options Xnio XnioWorker]))

(set! *warn-on-reflection* true)

(defn- headers->map
  [^HeaderMap headers]
  (reduce
   (fn [acc ^HeaderValues values]
     (assoc acc
            (keyword (str (.getHeaderName values)))
            (.getFirst values)))
   {}
   headers))

(defn- query-params->map
  [^HttpServerExchange exchange]
  (reduce
   (fn [acc ^Map$Entry entry]
     (assoc acc
            (keyword (.getKey entry))
            (.peekFirst ^Deque (.getValue entry))))
   {}
   (.getQueryParameters exchange)))

(defn ->http-request
  "The HTTP request of an exchange in the shape lambda.http handles."
  [^HttpServerExchange exchange]
  {:path (.getRequestPath exchange)
   :httpMethod (str (.getRequestMethod exchange))
   :headers (headers->map (.getRequestHeaders exchange))
   :queryStringParameters (query-params->map exchange)
   :body (slurp (.getInputStream exchange) :encoding "UTF-8")})

(defn- response-bytes
  ^bytes [{:keys [body isBase64Encoded]}]
  (if isBase64Encoded
    (.decode (Base64/getDecoder) ^String body)
    (.getBytes (str body) StandardCharsets/UTF_8)))

(defn- write-response!
  [^HttpServerExchange exchange response]
  (let [{:keys [statusCode headers]}
        response

        response-headers
        (.getResponseHeaders exchange)]
    (.setStatusCode exchange (int statusCode))
    (doseq [[k v] headers]
      (.put response-headers (HttpString. (name k)) (str v)))
    (with-open [out (.getOutputStream exchange)]
      (.write out (response-bytes response)))))

(defn handle-request
  "Runs a request in the shape lambda.http handles ({:path :httpMethod
   :headers :queryStringParameters :body}) in-process and returns its HTTP
   response {:statusCode :headers :body :isBase64Encoded}, with the
   authentication, filters and distribution of a request to the default
   listener. For callers without an exchange; what lambda.http doesn't
   answer itself is thrown."
  [{:keys [ctx-fn cache]} request]
  (runtime/with-invocation cache {:scoped true}
    (http/handle (assoc (ctx-fn) :invocation-id (uuid/gen)) request)))

(defn handle-edd
  "Answers an exchange as the default listener does: its request goes
   through handle-request, a failure answers 500. For a listener's own
   :handler that serves edd requests next to its own. Call it on a worker
   thread with blocking IO started, as listener handlers are."
  [runtime ^HttpServerExchange exchange]
  (let [response
        (try
          (handle-request runtime (->http-request exchange))
          (catch Exception e
            (log/error e "Undertow request failed")
            {:statusCode 500
             :headers {"Content-Type" "application/json"}
             :body (util/to-json {:error (ex-message e)})}))]
    (write-response! exchange response)))

(defn handle-record
  "Handles one queue record like a Lambda SQS trigger, including response
   distribution. Returns true when it succeeded."
  [ctx-fn cache record]
  (runtime/with-invocation cache {:scoped true}
    (try
      (let [ctx
            (assoc (ctx-fn) :invocation-id (uuid/gen))

            records
            [record]]
        (not (runtime/complete-records! ctx records (runtime/handle-records ctx records))))
      (catch Exception e
        (log/error e "Queue record failed")
        false))))

(defn- blocking-handler
  "Hands the exchange, untouched, to handle on a worker thread with blocking
   IO started."
  ^HttpHandler [handle]
  (reify HttpHandler
    (handleRequest [this exchange]
      (if (.isInIoThread exchange)
        (.dispatch exchange ^HttpHandler this)
        (do
          (.startBlocking exchange)
          (handle exchange))))))

(defn- default-listener
  [{:keys [port host listeners]}]
  (let [given
        (:default listeners)]
    (when (or given (not (contains? listeners :default)))
      (merge {:port (or port (Long/parseLong (util/get-env "Port" "8080")))
              :host (or host (util/get-env "Host" "0.0.0.0"))
              :handler handle-edd}
             given))))

(defn- own-listener
  [listener-name {:keys [handler port] :as listener}]
  (when-not (and (ifn? handler) (some? port))
    (throw (ex-info (str "Listener " listener-name " needs a :port and a :handler")
                    {:listener listener-name})))
  (merge {:host "0.0.0.0"} listener))

(defn- listeners-of
  "Listeners by name; one given as false or nil is left out."
  [{:keys [listeners] :as opts}]
  (let [default
        (default-listener opts)

        own
        (into {}
              (for [[listener-name listener] (dissoc listeners :default)
                    :when listener]
                [listener-name (own-listener listener-name listener)]))]
    (cond-> own
      default (assoc :default default))))

(defn- shared-worker
  "One XNIO worker for every listener, sized like Undertow's own unless
   :io-threads/:worker-threads say otherwise."
  ^XnioWorker [{:keys [io-threads worker-threads]}]
  (let [io
        (int (or io-threads (max 2 (.availableProcessors (Runtime/getRuntime)))))

        tasks
        (int (or worker-threads (* 8 io)))]
    (.createWorker (Xnio/getInstance (.getClassLoader Undertow))
                   (-> (OptionMap/builder)
                       (.set Options/WORKER_IO_THREADS io)
                       (.set Options/WORKER_TASK_CORE_THREADS tasks)
                       (.set Options/WORKER_TASK_MAX_THREADS tasks)
                       (.set Options/CONNECTION_HIGH_WATER (int 1000000))
                       (.set Options/CONNECTION_LOW_WATER (int 1000000))
                       (.set Options/TCP_NODELAY true)
                       (.set Options/CORK true)
                       (.getMap)))))

(defn- with-limits
  ^Undertow$Builder [^Undertow$Builder builder {:keys [max-entity-size request-parse-timeout no-request-timeout read-timeout]}]
  (cond-> builder
    max-entity-size (.setServerOption UndertowOptions/MAX_ENTITY_SIZE (long max-entity-size))
    request-parse-timeout (.setServerOption UndertowOptions/REQUEST_PARSE_TIMEOUT (int request-parse-timeout))
    no-request-timeout (.setServerOption UndertowOptions/NO_REQUEST_TIMEOUT (int no-request-timeout))
    read-timeout (.setSocketOption Options/READ_TIMEOUT (int read-timeout))))

(defn- server
  ^Undertow [^XnioWorker worker runtime {:keys [port host handler] :as listener}]
  (-> (Undertow/builder)
      (.setWorker worker)
      (.addHttpListener (int port) ^String host)
      (with-limits listener)
      (.setHandler (blocking-handler (fn [exchange] (handler @runtime exchange))))
      (.build)))

(defrecord UndertowRuntime [servers worker ctx-fn cache consumers])

(defn port
  "The bound port of a listener: the named one, else the default listener
   or the only one."
  ([{:keys [servers] :as runtime}]
   (port runtime (if (contains? servers :default) :default (first (keys servers)))))
  ([{:keys [servers]} listener]
   (let [^Undertow$ListenerInfo info
         (first (.getListenerInfo ^Undertow (get servers listener)))]
     (.getPort ^InetSocketAddress (.getAddress info)))))

(defn- stop-listeners
  [servers ^XnioWorker worker]
  (doseq [^Undertow started (vals servers)]
    (.stop started))
  (.shutdown worker))

(defn start
  "HTTP runtime with the same contract as the Lambda custom runtime,
   so a service registers once and runs on Lambda or Undertow.

   HTTP requests go through lambda.http. Responses are distributed to the
   router like on Lambda, and the service's command and event sources are
   consumed (edd.distribution/consume-sources, override with :consume, []
   disables), all through the registered distributor. Every invocation
   runs on edd.ctx/invocation-ctx, so features keep what expires fresh (AWS
   credentials). Listens on :port/:host,
   falling back to the Port/Host env vars; :filters are runtime filters (see
   lambda.runtime).

   :listeners names more listeners, one Undertow server each on a shared
   worker pool (:io-threads, :worker-threads):

     {:default {:port 8081 :host \"127.0.0.1\"}
      :public  {:port 8000 :handler (fn [runtime exchange])}}

   :default is the listener above (:default false: none) and serves edd
   requests unless it names a :handler. Every other listener names its
   :port and :handler, called on a worker thread with blocking IO started
   and the HttpServerExchange as Undertow made it; handle-edd answers an
   edd request from there, handle-request runs one without an exchange.
   A listener may set :host (0.0.0.0), :max-entity-size (bytes) and
   :request-parse-timeout, :no-request-timeout, :read-timeout (ms)."
  [init-ctx handler & {:keys [consume] :as opts}]
  (let [listeners
        (listeners-of opts)

        cache
        (atom {})

        consuming
        (cond-> init-ctx
          (some? consume) (assoc :edd/consume consume))

        ctx
        (binding [util/*cache* cache]
          (runtime/start consuming handler (select-keys opts [:filters :post-filter])))

        ctx-fn
        (fn []
          (assoc (edd-ctx/invocation-ctx ctx)
                 ::distribution/ctx-fn #(edd-ctx/invocation-ctx ctx)))

        worker
        (shared-worker opts)

        runtime
        (promise)

        servers
        (update-vals listeners #(server worker runtime %))

        _
        (try
          (doseq [^Undertow built (vals servers)]
            (.start built))
          (catch Exception e
            (stop-listeners servers worker)
            (throw e)))

        consumers
        (mapv #(distribution/start-consumer! (ctx-fn) % (partial handle-record ctx-fn cache))
              (distribution/consume-sources ctx))

        started
        (->UndertowRuntime servers worker ctx-fn cache consumers)]
    (deliver runtime started)
    (log/info "Undertow listening" {:listeners (into {}
                                                     (map (fn [[listener-name {:keys [host]}]]
                                                            [listener-name {:host host
                                                                            :port (port started listener-name)}]))
                                                     listeners)
                                    :service-name (:service-name ctx)
                                    :consumers (count consumers)})
    started))

(defn stop
  [{:keys [servers worker consumers]}]
  (doseq [stop-consumer consumers]
    (stop-consumer))
  (stop-listeners servers worker))
