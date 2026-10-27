(ns lambda.http
  "HTTP requests of every runtime, in the API Gateway proxy shape
   {:path :httpMethod :headers :queryStringParameters :body}: the request
   body or the GET request parameter becomes the edd request, the user comes
   from the feature providing :resolve-user, router payloads are distributed
   and the response is encoded with CORS headers, gzip when accepted and
   status 500 for errors."
  (:require [clojure.string :as string]
            [clojure.tools.logging :as log]
            [edd.ctx :as edd-ctx]
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

(defn assign-metadata
  "Puts realm and user into ctx :meta, the user coming from the feature
   providing :resolve-user (fn [ctx] user), e.g. aws.cognito/register."
  [{:keys [body] :as ctx}]
  (let [{:keys [meta]}
        body

        resolve-user
        (or (edd-ctx/feature-fn ctx :resolve-user)
            (throw (ex-info "No authentication registered"
                            {:message "No authentication registered; register one, e.g. (aws.cognito/register ctx)"})))

        {:keys [role] :as user}
        (resolve-user ctx)]
    (if (= :non-interactive
           role)
      (assoc ctx :meta meta)
      (assoc ctx :meta (assoc meta
                              :realm (get-realm ctx user {})
                              :user (dissoc user :realm))))))

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
         (runtime/handle (assign-metadata (assoc ctx :body edd-request))
                         edd-request))))))

(defn handle
  "Runs an HTTP request through the runtime filters and handler as the
   authenticated user, distributes the router payloads and returns the HTTP
   response."
  [ctx request]
  (log-debug-params request)
  (let [ctx
        (assoc ctx
               :from-api true
               :req request)

        resp
        (runtime/distribute! ctx (handle-request ctx request))]
    (response ctx request resp)))
