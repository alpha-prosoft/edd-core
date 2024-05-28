(ns lambda.util
  (:require
   [clojure.java.io :as io]
   [clojure.set :as clojure-set]
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [jsonista.core :as json]
   [lambda.aes :as aes]
   [org.httpkit.client :as http])
  (:import
   (clojure.lang Keyword)
   (com.fasterxml.jackson.core JsonGenerator)
   (com.fasterxml.jackson.databind ObjectMapper)
   (com.fasterxml.jackson.databind.module SimpleModule)
   (java.io BufferedReader File)
   (java.math BigInteger)
   (java.net URLEncoder)
   (java.nio.charset Charset)
   (java.nio.charset StandardCharsets)
   (java.security MessageDigest)
   (java.time OffsetDateTime)
   (java.time.format DateTimeFormatter)
   (java.util Base64 Date UUID)
   (javax.crypto Mac)
   (javax.crypto.spec SecretKeySpec)
   (jsonista.jackson FunctionalUUIDKeySerializer)))

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)

(def offset-date-time-format "yyyy-MM-dd'T'HH:mm:ss.SSSXXX")

(defn decode-json-special
  [^String v]
  (case (first v)
    \: (if (str/starts-with? v "::")
         (subs v 1)
         (keyword (subs v 1)))
    \# (if (str/starts-with? v "##")
         (subs v 1)
         (UUID/fromString (subs v 1)))
    v))

(def edd-core-module
  (doto (SimpleModule. "EddCore")
    (.addKeySerializer
     UUID
     (FunctionalUUIDKeySerializer. (partial str "#")))))

(def ^ObjectMapper json-mapper
  (json/object-mapper
   {:modules [edd-core-module]
    :decode-key-fn
    (fn [v]
      (case (first v)
        \# (if (str/starts-with? v "##")
             (subs v 1)
             (UUID/fromString (subs v 1)))
        (keyword v)))
    :decode-fn
    (fn [v]
      (condp instance? v
        String (decode-json-special v)
        v))
    :encoders      {String         (fn [^String v ^JsonGenerator jg]
                                     (cond
                                       (str/starts-with? v ":")
                                       (.writeString jg (str ":" v))
                                       (str/starts-with? v "#")
                                       (.writeString jg (str "#" v))
                                       :else (.writeString jg v)))
                    BufferedReader (fn [^BufferedReader _v ^JsonGenerator jg]
                                     (.writeString jg "BufferedReader"))
                    UUID           (fn [^UUID v ^JsonGenerator jg]
                                     (.writeString jg (str "#" v)))
                    Keyword        (fn [^Keyword v ^JsonGenerator jg]
                                     (.writeString jg (str ":" (name v))))}}))

(defn date-time
  (^OffsetDateTime [] (OffsetDateTime/now))
  (^OffsetDateTime [^String value] (OffsetDateTime/parse value)))

(defn date->string
  ([]
   (.format (date-time)
            (DateTimeFormatter/ofPattern offset-date-time-format)))
  ([^OffsetDateTime date]
   (.format date
            (DateTimeFormatter/ofPattern offset-date-time-format))))

(defn get-current-time-ms
  []
  (System/currentTimeMillis))

(defn is-in-past
  [^Date date]
  (.before date (new Date)))

(defn to-edn
  [json]
  (json/read-value json json-mapper))

(defn to-json
  [edn]
  (json/write-value-as-string edn json-mapper))

(defn wrap-body [request]
  (cond
    (:form-params request) request
    (string? request) {:body request}
    :else {:body (to-json request)}))

(defn http-request
  [url request & {:keys [raw]}]
  (log/debug url request)
  (let [resp @(http/request (assoc request
                                   :url url))]
    (log/debug "Response" resp)
    (if raw
      resp
      (assoc resp
             :body (to-edn (:body resp))))))

(defn http-get
  [url & {:keys [raw]
          :or {raw false}}]
  (http-request url {:method :get} :raw raw))

(defn http-delete
  [url request & {:keys [raw]
                  :or {raw false}}]
  (http-request url (assoc request
                           :method :delete)
                :raw raw))

(defn http-put
  [url request & {:keys [raw]
                  :or {raw false}}]
  (log/debug url request)
  (http-request url (assoc (wrap-body request)
                           :method :put) :raw raw))

(defn http-post
  [url request & {:keys [raw]
                  :or {raw false}}]
  (http-request url (assoc (wrap-body request)
                           :method :post) :raw raw))

(defn get-env
  [name & [default]]
  (get (System/getenv) name default))

(defn get-property
  [name & [default]]
  (get (System/getProperties) name default))

(defn escape
  [value]
  (str/replace value "\"" "\\\""))

(defn decrypt
  [body ^String name]
  (log/debug "Decrypting")
  (let [context (get-env "ConfigurationContext")]
    (if (and context
             (.contains name "secret"))
      (let [context (str/split context #":")
            iv (first context)
            key (second context)]
        (aes/decrypt (str/replace body #"\n" "")
                     key
                     iv))
      body)))

(defn compatibility->aws-user-pool
  [config]
  (if (get-in config [:auth :client-id])
    (update config
            :auth (fn [{:keys [client-id
                               user-pool-id]
                        :as auth}]
                    (assoc auth
                           :iss (str "https://cognito-idp."
                                     (get-env "Region")
                                     ".amazonaws.com/"
                                     user-pool-id)
                           :aud client-id)))
    config))

(defn load-config
  [name]
  (log/debug "Loading config name:" name)
  (let [file (io/as-file name)
        config (to-edn
                (if (.exists ^File file)
                  (do
                    (log/debug "Loading from file config:" name)
                    (-> file
                        (slurp)
                        (decrypt name)))
                  (do
                    (log/debug "Loading config from classpath:" name)
                    (-> (slurp (io/resource name))
                        (decrypt name)))))
        config (compatibility->aws-user-pool config)
        env-config (to-edn
                    (get-env "CustomConfig" "{}"))]
    (merge config env-config)))

(defn base64encode
  [^String to-encode]
  (.encodeToString (Base64/getEncoder)
                   (.getBytes to-encode "UTF-8")))

(defn bytes->base64encode
  [to-encode]
  (.encodeToString (Base64/getEncoder)
                   to-encode))

(defn base64decode
  [^String to-decode]
  (String. (.decode
            (Base64/getDecoder)
            to-decode) "UTF-8"))

(defn base64URLdecode
  [^String to-decode]
  (String. (.decode
            (Base64/getUrlDecoder)
            to-decode) "UTF-8"))

(def ^:dynamic *cache*)

(defn hmac-sha256
  [^String secret ^String message]
  (let [mac (Mac/getInstance "HmacSHA256")
        secret-key-spec (new SecretKeySpec
                             (.getBytes secret "UTF-8")
                             "HmacSHA256")
        message-bytes (.getBytes message "UTF-8")]
    (.init mac secret-key-spec)
    (->> message-bytes
         (.doFinal mac)
         (.encodeToString (Base64/getEncoder)))))

(defn url-encode
  [^String message]
  (let [^Charset charset (Charset/forName "UTF-8")]
    (URLEncoder/encode message charset)))

(defmacro d-time
  "Evaluates expr and logs time it took.  Returns the value of
 expr."
  {:added "1.0"}
  [message & expr]
  `(do
     (log/info {:message (str "START " ~message)})

     (let [start# (. System (nanoTime))
           mem# (-> (- (.totalMemory (Runtime/getRuntime))
                       (.freeMemory (Runtime/getRuntime)))
                    (/ 1024)
                    (/ 1024)
                    (int))
           ret# (do ~@expr)]
       (log/info {:type    :time
                  :message (str "END " ~message)
                  :elapsed (/ (double (- (. System (nanoTime)) start#)) 1000000.0)
                  :memory  (str mem# " -> " (-> (- (.totalMemory (Runtime/getRuntime))
                                                   (.freeMemory (Runtime/getRuntime)))
                                                (/ 1024)
                                                (/ 1024)
                                                (int)))
                  :unit    "msec"})
       ret#)))

(defn exception->response
  [^Exception e]
  (let [data (ex-data e)]
    (if data
      (if (:exception data)
        data
        {:exception data})
      {:exception (try (.getMessage e)
                       (catch IllegalArgumentException e
                         (log/error e)
                         e))})))

(defn try->data
  [handler]
  (try (handler)
       (catch Exception e
         (exception->response e))))

(defn try->error
  [handler]
  (try (handler)
       (catch Exception e
         (clojure-set/rename-keys (exception->response e)
                                  {:exception :error}))))

(defn fix-keys
  "This is used to represent as close as possible when we store
  to external storage as JSON. Because we are using :keywordize keys
  for convenience. Problem is when map keys are in aggregate stored as strings.
  Then when they are being read from storage they are being keywordized.
  This is affecting when we are caching aggregate between calls because in
  this case cached aggregate would not represent real aggregate without cache.
  Other scenario is for tests because in tests we would get aggregate with string
  keys while in real scenario we would have keys as keywords."
  [val]
  (-> val
      (to-json)
      (to-edn)))

(defn log-startup
  []
  (let [startup-milis (Long/parseLong
                       (str
                        (get-property "edd.startup-milis" 0)))]
    (when (not= startup-milis 0)
      (log/info "Server started: " (- (System/currentTimeMillis)
                                      startup-milis)))))

(defn md5hadh [^String s]
  (let [algorithm (MessageDigest/getInstance "MD5")
        raw (.digest algorithm (.getBytes s))]
    (format "%032x" (BigInteger. 1 raw))))

(defn string->md5base64 [^String s]
  (let [algorithm (MessageDigest/getInstance "MD5")
        raw (.digest algorithm (.getBytes s))]
    (bytes->base64encode raw)))

(defn slurp-bytes
  "Slurp the bytes from a slurpable thing"
  [^String x]
  (with-open [out (java.io.ByteArrayOutputStream.)]
    (with-open [file (clojure.java.io/input-stream x)]
      (clojure.java.io/copy file out))
    (.toByteArray out)))

(defn path->md5base64 [^String path]
  (let [algorithm (MessageDigest/getInstance "MD5")
        raw (.digest algorithm  (slurp-bytes path))]
    (bytes->base64encode raw)))

(defn sha256 [^String s]
  (let [algorithm (MessageDigest/getInstance "MD5")
        raw (.digest algorithm (.getBytes s))]
    (format "%032x" (BigInteger. 1 raw))))

(defn thread-sleep
  [^long milis]
  (Thread/sleep milis))
