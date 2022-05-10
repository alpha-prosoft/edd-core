(ns lambda.util
  (:require [jsonista.core :as json]
            [clojure.tools.logging :as log]
            [org.httpkit.client :as http]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.set :as clojure-set]
            [lambda.aes :as aes])
  (:import (java.time OffsetDateTime)
           (java.time.format DateTimeFormatter)
           (java.io File BufferedReader)
           (java.util UUID Date Base64)
           (javax.crypto Mac)
           (javax.crypto.spec SecretKeySpec)
           (com.fasterxml.jackson.core JsonGenerator)
           (clojure.lang Keyword)
           (com.fasterxml.jackson.databind ObjectMapper)
           (java.nio.charset Charset)
           (java.net URLEncoder)))

(def offset-date-time-format "yyyy-MM-dd'T'HH:mm:ss.SSSXXX")

(def ^ObjectMapper json-mapper
  (json/object-mapper
   {:decode-key-fn true
    :decode-fn
    (fn [v]
      (condp instance? v
        String (case (first v)
                 \: (if (str/starts-with? v "::")
                      (subs v 1)
                      (keyword (subs v 1)))
                 \# (if (str/starts-with? v "##")
                      (subs v 1)
                      (UUID/fromString (subs v 1)))
                 v)
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
  ([] (OffsetDateTime/now))
  ([^String value] (OffsetDateTime/parse value)))

(defn date->string
  ([] (.format (date-time) (DateTimeFormatter/ofPattern offset-date-time-format)))
  ([^OffsetDateTime date] (.format date (DateTimeFormatter/ofPattern offset-date-time-format))))

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
  [body name]
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
        classpath (io/as-file
                   (io/resource
                    name))
        config (to-edn
                (if (.exists ^File file)
                  (do
                    (log/debug "Loading from file config:" name)
                    (-> file
                        (slurp)
                        (decrypt name)))
                  (do
                    (log/debug "Loading config from classpath:" name)
                    (-> classpath
                        (slurp)
                        (decrypt name)))))
        config (compatibility->aws-user-pool config)
        env-config (to-edn
                    (get-env "CustomConfig" "{}"))]
    (merge config env-config)))

(defn base64encode
  [^String to-encode]
  (.encodeToString (Base64/getEncoder)
                   (.getBytes to-encode "UTF-8")))

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
  [e]
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
