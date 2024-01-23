(ns sdk.aws.s3
  (:require [sdk.aws.common :as common]
            [lambda.util :as util]
            [lambda.http-client :as client]
            [clojure.data.xml :as xml]
            [clojure.tools.logging :as log]
            [clojure.java.io :as io]
            [clojure.string :as string]))

(defn get-host
  [ctx]
  (str "s3."
       (get-in ctx [:aws :region])
       ".amazonaws.com"))

(defn convert-query-params
  [params]
  (reduce
   (fn [p [k v]]
     (assoc p k v))
   {}
   params))

(defn- parse-response
  [response _ctx object]
  (log/debug "S3 response" response)
  (cond
    (contains? response :error) (do
                                  (log/error "Failed update" response)
                                  {:error (:error response)})
    (= (:status response 0) 404) nil
    (> (:status response 199) 399) {:error {:status  (:status response)
                                            :message (slurp (:body response))
                                            :key     (get-in object [:s3 :object :key])
                                            :bucket  (get-in object [:s3 :bucket :name])}}

    :else response))

(defn get-aws-token [{:keys [aws]}]
  (let [token (:aws-session-token aws)]
    (if (empty? token)
      (System/getenv "AWS_SESSION_TOKEN")
      token)))

(defn s3-request-helper [{:keys [aws] :as ctx} object]
  {:headers    (merge {"Host"                 (get-host ctx)
                       "x-amz-content-sha256" "UNSIGNED-PAYLOAD"
                       "x-amz-date"           (common/create-date)
                       "x-amz-security-token" (get-aws-token ctx)}
                      (get-in object [:s3 :headers]))
   :service    "s3"
   :region     (:region aws)
   :access-key (:aws-access-key-id aws)
   :secret-key (:aws-secret-access-key aws)})

(defn put-object
  "puts object.content (should be plain string) into object.s3.bucket.name under object.s3.bucket.key"
  [{:keys [aws] :as ctx} object]
  (let [req

        (merge (s3-request-helper ctx object)
               {:method     "PUT"
                :uri        (str "/"
                                 (get-in object [:s3 :bucket :name])
                                 "/"
                                 (get-in object [:s3 :object :key]))})
        common (common/authorize req)
        response (client/retry-n #(-> (util/http-request
                                       (str "https://"
                                            (get (:headers req) "Host")
                                            (:uri req))
                                       (client/request->with-timeouts
                                        %
                                        {:as      :stream
                                         :method  :put
                                         :headers (-> (:headers req)
                                                      (dissoc "Host")
                                                      (assoc "Authorization" common))
                                         :body    (get-in object [:s3 :object :content])})
                                       :raw true)
                                      (parse-response ctx object)))
        {:keys [error] :as response} response]
    (cond
      error response
      (= nil response) nil
      :else (io/reader (:body response) :encoding "UTF-8"))))

(defn get-object
  [{:keys [aws] :as ctx} object]
  (let [req
        (merge (s3-request-helper ctx object)
               {:method     "GET"
                :uri        (str "/"
                                 (get-in object [:s3 :bucket :name])
                                 "/"
                                 (get-in object [:s3 :object :key]))})
        common (common/authorize req)
        response (client/retry-n #(-> (util/http-request
                                       (str "https://"
                                            (get (:headers req) "Host")
                                            (:uri req))
                                       (client/request->with-timeouts
                                        %
                                        {:as      :stream
                                         :method  :get
                                         :headers (-> (:headers req)
                                                      (dissoc "Host")
                                                      (assoc "Authorization" common))})
                                       :raw true)
                                      (parse-response ctx object)))
        {:keys [error] :as response} response]
    (cond
      error response
      (= nil response) nil
      :else (io/reader (:body response) :encoding "UTF-8"))))

(defn delete-object
  [{:keys [aws] :as ctx} object]
  (let [req
        (merge (s3-request-helper ctx object)
               {:method     "DELETE"
                :uri        (str "/"
                                 (get-in object [:s3 :bucket :name])
                                 "/"
                                 (get-in object [:s3 :object :key]))})

        common (common/authorize req)
        response (client/retry-n #(-> (util/http-request
                                       (str "https://"
                                            (get (:headers req) "Host")
                                            (:uri req))
                                       (client/request->with-timeouts
                                        %
                                        {:as      :stream
                                         :method  :delete
                                         :headers (-> (:headers req)
                                                      (dissoc "host")
                                                      (assoc "Authorization" common))})
                                       :raw true)
                                      (parse-response ctx object)))
        {:keys [error] :as response} response]
    (cond
      error response
      (= nil response) nil
      :else {:body    (io/reader (:body response) :encoding "UTF-8")
             :headers (:headers response)})))

(defn get-object-tagging
  [{:keys [aws] :as ctx} object]
  (let [req {:method     "GET"
             :uri        (str "/" (get-in object [:s3 :object :key]))
             :query      [["tagging" "True"]]
             :headers    {"Host"                 (str
                                                  (get-in object [:s3 :bucket :name])
                                                  ".s3."
                                                  (:region aws)
                                                  ".amazonaws.com")
                          "Content-Type"         "application/json"
                          "Accept"               "application/json"
                          "x-amz-content-sha256" "UNSIGNED-PAYLOAD"
                          "x-amz-date"           (common/create-date)
                          "x-amz-security-token" (get-aws-token ctx)}
             :service    "s3"
             :region     (:region aws)
             :access-key (:aws-access-key-id aws)
             :secret-key (:aws-secret-access-key aws)}
        common (common/authorize req)
        response (client/retry-n #(-> (util/http-request
                                       (str "https://"
                                            (get (:headers req) "Host")
                                            (:uri req))
                                       (client/request->with-timeouts
                                        %
                                        {:as      :stream
                                         :method  :get
                                         :query-params (convert-query-params (:query req))
                                         :headers      (-> (:headers req)
                                                           (dissoc "host")
                                                           (assoc "Authorization" common))})
                                       :raw true)
                                      (parse-response ctx object)))
        {:keys [error] :as response} response]
    (cond
      error response
      (= nil response) nil
      :else (->> (xml/parse (:body response))
                 (:content)
                 (first)
                 (:content)
                 (mapv
                  (fn [{:keys [content]}]
                    (let [key (first content)
                          val (second content)]
                      (assoc
                       {}
                       (-> key
                           (:tag)
                           (name)
                           (string/lower-case)
                           (keyword))
                       (-> key
                           (:content)
                           (first))
                       (-> val
                           (:tag)
                           (name)
                           (string/lower-case)
                           (keyword))
                       (-> val
                           (:content)
                           (first))))))))))

(defn put-object-tagging
  [{:keys [aws] :as ctx} {:keys [object tags]}]
  (let [tags (xml/emit-str
              {:tag     "Tagging"
               :content [{:tag     "TagSet"
                          :content (mapv
                                    (fn [{:keys [key value]}]
                                      {:tag     "Tag"
                                       :content [{:tag     "Key"
                                                  :content [key]}
                                                 {:tag     "Value"
                                                  :content [value]}]})
                                    tags)}]})
        req {:method     "PUT"
             :uri        (str "/" (get-in object [:s3 :object :key]))
             :query      [["tagging" "True"]]
             :headers    {"Host"                 (str
                                                  (get-in object [:s3 :bucket :name])
                                                  ".s3."
                                                  (:region aws)
                                                  ".amazonaws.com")
                          "Content-Type"         "application/json"
                          "Accept"               "application/json"
                          "x-amz-content-sha256" "UNSIGNED-PAYLOAD"
                          "x-amz-date"           (common/create-date)
                          "x-amz-security-token" (get-aws-token ctx)}
             :service    "s3"
             :region     (:region aws)
             :payload    tags
             :access-key (:aws-access-key-id aws)
             :secret-key (:aws-secret-access-key aws)}
        common (common/authorize req)
        response (client/retry-n #(-> (util/http-request
                                       (str "https://"
                                            (get (:headers req) "Host")
                                            (:uri req))
                                       (client/request->with-timeouts
                                        %
                                        {:as      :stream
                                         :method  :put
                                         :query-params (convert-query-params (:query req))
                                         :body         (:payload req)
                                         :headers      (-> (:headers req)
                                                           (dissoc "host")
                                                           (assoc "Authorization" common))})
                                       :raw true)
                                      (parse-response ctx object)))
        {:keys [error] :as response} response]
    (if error
      response
      {:version (get-in response [:headers :x-amz-version-id])})))

(defn encode-query
  [query]
  (map
   (fn [[k v]]
     [k (util/url-encode v)])
   query))

(defn presigned-url
  [{:keys [aws debug] :as ctx}
   {:keys [object method expires]
    :or {method "GET"}}]
  (let [host (str (get-in object [:s3 :bucket :name])
                  ".s3."
                  (get-in ctx [:aws :region])
                  ".amazonaws.com")
        path (str "/" (get-in object [:s3 :object :key]))
        date (common/create-date)
        query [["X-Amz-Algorithm" "AWS4-HMAC-SHA256"]
               ["X-Amz-Credential" (str
                                    (:aws-access-key-id aws)
                                    "/"
                                    (first
                                     (string/split date #"T"))
                                    "/"
                                    (:region aws)
                                    "/s3/aws4_request")]
               ["X-Amz-Expires" (str expires)]
               ["X-Amz-Date" (common/create-date)]
               ["X-Amz-Security-Token" (get-aws-token ctx)]
               ["X-Amz-SignedHeaders" "host"]
               #_["X-amz-Content-MD5" md5]]
        req {:method     method
             :uri        path
             :query      query
             :headers    {"Host" host}
             :service    "s3"
             :region     (:region aws)
             :access-key (:aws-access-key-id aws)
             :secret-key (:aws-secret-access-key aws)}
        auth (common/authorize (assoc req
                                      :payload "UNSIGNED-PAYLOAD"
                                      :date date
                                      :debug debug))
        [_credential _headers signature] (string/split auth #"[,][ ]")
        signature (-> signature
                      (string/split #"=")
                      second)
        signature-query [["X-Amz-Signature" signature]]
        query (concat query signature-query)
        query (encode-query query)
        query (string/join "&"
                           (map
                            (fn [[k v]]
                              (str k "=" v))
                            query))
        url (str (format "https://%s%s?%s"
                         host
                         path
                         query))]
    (println url)
    url))
