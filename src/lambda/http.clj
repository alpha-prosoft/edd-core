(ns lambda.http
  "HTTP requests of every runtime, in the API Gateway proxy shape
   {:path :httpMethod :headers :queryStringParameters :body}: the request
   body or the GET request parameter becomes the edd request, an
   authentication filter (authenticate) resolves its user, router payloads
   are distributed and the response is encoded with CORS headers, gzip when
   accepted and status 500 for errors."
  (:require [clojure.string :as string]
            [clojure.tools.logging :as log]
            [lambda.codec :as codec]
            [lambda.gzip :as gzip]
            [lambda.runtime :as runtime]
            [lambda.util :as util]))

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)

(defn request?
  [event]
  (contains? event :path))

(defn parse-query-string
  [request]
  (reduce
   (fn [p [key value]]
     (let [keys
           (map keyword (remove empty? (string/split key #"\[|\]")))]
       (assoc-in p keys (util/decode-json-special value))))
   {}
   (map #(string/split % #"=") (string/split request #"&"))))

(defn get-realm
  [ctx {:keys [realm roles]} _]
  (let [default-realm (get-in ctx [:auth :default-realm])
        realm (or realm
                  default-realm)]
    (when-not realm
      (throw (ex-info (str "Realm: " realm) {:error         "Missing realm in request token"
                                             :realm         realm
                                             :roles         roles
                                             :default-realm default-realm})))
    realm))

(defn non-interactive
  [user]
  (first
   (filter
    #(= % :non-interactive)
    (:roles user))))

(defn select-role
  "The role a user with :roles acts in for a request: the one the request
   selects ({:user {:selected-role r}}), which has to be one of its roles
   unless the user is :non-interactive; else :non-interactive when it has
   it; else its first role that is neither :anonymous nor a realm-* marker;
   :anonymous when nothing is left."
  [{:keys [roles] :as user} request]
  (let [selected
        (get-in request [:user :selected-role])

        service
        (non-interactive user)]
    (when (and selected
               (not service)
               (not-any? #(= selected %) roles))
      (throw (ex-info "Selecting non-existing role"
                      {:message "Selecting non-existing role"
                       :selected-role selected
                       :roles roles})))
    (or selected
        service
        (first (remove #(or (= :anonymous %)
                            (string/starts-with? (name %) "realm-"))
                       roles))
        :anonymous)))

(defn with-user
  "ctx of an HTTP request running as user: realm and user in ctx :meta on
   top of the request :meta. A :non-interactive user is trusted with the
   :meta of its request. An authentication filter calls this, the runtime
   refuses an HTTP request no filter resolved a user for."
  [ctx request {:keys [role] :as user}]
  (let [{:keys [meta]}
        request

        with-meta
        (if (= :non-interactive role)
          (assoc ctx :meta meta)
          (assoc ctx :meta (assoc meta
                                  :realm (get-realm ctx user {})
                                  :user (dissoc user :realm))))]
    (assoc with-meta ::user user)))

(defn authenticate
  "Runtime filter authenticating HTTP requests: resolve-user is (fn [ctx])
   returning the user of the request in ctx (:req, :body) or throwing.
   Requests from other sources pass through."
  [resolve-user]
  (fn [ctx request chain]
    (if (:from-api ctx)
      (chain (with-user ctx request (resolve-user ctx)) request)
      (chain ctx request))))

(defn- guarded
  "The handler behind the filters: an HTTP request has to arrive with a
   resolved user."
  [handler]
  (fn [ctx request]
    (when-not (::user ctx)
      (let [message
            (str "No user resolved for the HTTP request; add an authentication filter to :filters, "
                 "e.g. (lambda.http/authenticate resolve-user)")]
        (throw (ex-info message {:message message}))))
    (handler ctx request)))

(defn- edd-header?
  [header-name]
  (string/starts-with?
   (string/lower-case (name header-name))
   "edd-"))

(defn- collect-edd-headers
  [headers multi-value-headers]
  (let [from-headers
        (reduce-kv
         (fn [acc k v]
           (if (edd-header? k)
             (assoc acc (string/lower-case (name k)) v)
             acc))
         {}
         (or headers {}))

        from-multi
        (reduce-kv
         (fn [acc k v]
           (if (edd-header? k)
             (assoc acc (string/lower-case (name k)) (string/join "," v))
             acc))
         {}
         (or multi-value-headers {}))]
    (merge from-headers from-multi)))

(defn- log-debug-params
  [request]
  (let [{:keys [request-id test-name]}
        (:queryStringParameters request)

        edd-headers
        (collect-edd-headers (:headers request) (:multiValueHeaders request))]
    (when (and request-id test-name)
      (log/info "request-id=" request-id "&test-name=" test-name))
    (when (seq edd-headers)
      (log/info "edd-headers:" edd-headers))))

(defn- health-check?
  [{:keys [path httpMethod]}]
  (or (= "/health" path)
      (= "OPTIONS" httpMethod)))

(defn- body
  [{:keys [httpMethod queryStringParameters] :as request}]
  (if (= "GET" httpMethod)
    (parse-query-string (:request queryStringParameters))
    (util/to-edn (:body request))))

(defn response
  "HTTP response of an edd response."
  [{:keys [resp-content-type resp-serializer-fn]
    :or {resp-content-type "application/json"
         resp-serializer-fn util/to-json}}
   request
   resp]
  (let [origin
        (or (get-in request [:headers :Origin])
            (get-in request [:headers :origin]))

        {:keys [exception error]}
        resp

        [serializer-error? content]
        (try
          [nil (resp-serializer-fn resp)]
          (catch Exception e
            (log/error e "serialization error, func: %s" resp-serializer-fn)
            [true (resp-serializer-fn {:error (format "could not serialize response, reason: %s" (ex-message e))})]))

        error?
        (or exception error serializer-error?)

        status
        (if error?
          500
          200)

        http-base
        {:statusCode status
         :headers (cond-> {"Access-Control-Allow-Headers"  "Id, VersionId, X-Authorization,Content-Type,X-Amz-Date,Authorization,X-Api-Key,X-Amz-Security-Token"
                           "Access-Control-Allow-Methods"  "OPTIONS,POST,PUT,GET"
                           "Access-Control-Expose-Headers" "*"
                           "Content-Type"                  resp-content-type
                           "Access-Control-Allow-Origin"   (or origin "*")}
                    origin (assoc "Access-Control-Allow-Credentials" "true"))}

        gzip?
        (and (not error?)
             (gzip/accepts-gzip? request))

        http-data
        (cond
          error?
          {:isBase64Encoded false
           :body content}

          gzip?
          (gzip/sub-response-gzip content)

          :else
          (gzip/sub-response content))]
    (log/infof "HTTP response, status: %s, origin body size: %s, accepts gzip: %s, final payload size: %s"
               status
               (some-> content codec/string->bytes alength)
               (boolean gzip?)
               (some-> http-data :body codec/string->bytes alength))
    (merge-with merge http-base http-data)))

(defn- handle-request
  [ctx request]
  (if (health-check? request)
    {:healthy true
     :build-id (util/get-env "BuildId" "b0")}
    (runtime/handle-with
     ctx
     (fn []
       (let [edd-request
             (body request)]
         (runtime/handle (assoc ctx
                                :body edd-request
                                :handler (guarded (:handler ctx)))
                         edd-request))))))

(defn handle
  "Runs an HTTP request through the runtime filters and the handler,
   distributes the router payloads and returns the HTTP response."
  [ctx request]
  (log-debug-params request)
  (let [ctx
        (assoc ctx
               :from-api true
               :req request)

        resp
        (runtime/distribute! ctx (handle-request ctx request))]
    (response ctx request resp)))
