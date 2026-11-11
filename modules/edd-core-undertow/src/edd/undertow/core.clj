(ns edd.undertow.core
  (:require [lambda.runtime :as runtime]
            [lambda.http :as http]
            [clojure.tools.logging :as log]
            [edd.ctx :as edd-ctx]
            [edd.distribution :as distribution]
            [lambda.util :as util]
            [lambda.uuid :as uuid])
  (:import [io.undertow Undertow Undertow$ListenerInfo]
           [io.undertow.server HttpHandler HttpServerExchange]
           [io.undertow.util HeaderMap HeaderValues HttpString]
           [java.net InetSocketAddress]
           [java.nio.charset StandardCharsets]
           [java.util Base64 Deque Map$Entry]))

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

(defn- handle-exchange
  [ctx-fn cache ^HttpServerExchange exchange]
  (runtime/with-invocation cache {:scoped true}
    (try
      (let [ctx
            (assoc (ctx-fn) :invocation-id (uuid/gen))

            request
            (->http-request exchange)]
        (write-response! exchange (http/handle ctx request)))
      (catch Exception e
        (log/error e "Undertow request failed")
        (write-response! exchange {:statusCode 500
                                   :headers {"Content-Type" "application/json"}
                                   :body (util/to-json {:error (ex-message e)})})))))

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

(defn- http-handler
  [ctx-fn cache]
  (reify HttpHandler
    (handleRequest [this exchange]
      (if (.isInIoThread exchange)
        (.dispatch exchange ^HttpHandler this)
        (do
          (.startBlocking exchange)
          (handle-exchange ctx-fn cache exchange))))))

(defrecord UndertowRuntime [^Undertow server consumers])

(defn port
  [{:keys [^Undertow server]}]
  (let [^Undertow$ListenerInfo listener
        (first (.getListenerInfo server))]
    (.getPort ^InetSocketAddress (.getAddress listener))))

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
   lambda.runtime)."
  [init-ctx handler & {:keys [port host consume] :as opts}]
  (let [cache
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

        listen-port
        (int (or port (Long/parseLong (util/get-env "Port" "8080"))))

        listen-host
        (or host (util/get-env "Host" "0.0.0.0"))

        server
        (.build
         (doto (Undertow/builder)
           (.addHttpListener listen-port ^String listen-host)
           (.setHandler (http-handler ctx-fn cache))))

        _
        (.start server)

        consumers
        (mapv #(distribution/start-consumer! (ctx-fn) % (partial handle-record ctx-fn cache))
              (distribution/consume-sources ctx))

        runtime
        (->UndertowRuntime server consumers)]
    (log/info "Undertow listening" {:host listen-host
                                    :port (edd.undertow.core/port runtime)
                                    :service-name (:service-name ctx)
                                    :consumers (count consumers)})
    runtime))

(defn stop
  [{:keys [^Undertow server consumers]}]
  (doseq [stop-consumer consumers]
    (stop-consumer))
  (.stop server))
