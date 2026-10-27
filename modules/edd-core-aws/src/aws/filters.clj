(ns aws.filters
  "Runtime filters for AWS event sources."
  (:require [clojure.string :as string]
            [clojure.tools.logging :as log]
            [lambda.uuid :as uuid]
            [sdk.aws.s3 :as s3]))

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)

(defn parse-key
  [key]
  (try
    (let [parts (string/split key #"/")
          realm (first parts)
          parts (rest parts)
          realm (if (= realm "upload")
                  "prod"
                  realm)
          date (if (re-matches #"[\d]{4}-[\d]{2}-[\d]{2}" (first parts))
                 (first parts)
                 (throw (ex-info "Missing date" {:parts parts
                                                 :error "Missing date"})))
          parts (rest parts)
          interaction-id (first parts)
          parts (rest parts)
          id (if (= 2 (count parts))
               (first parts)
               (-> parts
                   (first)
                   (string/split #"\.")
                   (first)))
          request-id (if (= 2 (count parts))
                       (-> parts
                           (second)
                           (string/split #"\.")
                           (first))
                       id)]
      {:request-id     (uuid/parse request-id)
       :interaction-id (uuid/parse interaction-id)
       :id             (uuid/parse (or id request-id))
       :date           date
       :realm          realm})
    (catch Exception e
      (log/error "Unable to parse key. Should be in format
                  /{{ realm }}/{{ yyyy-MM-dd }}/{{ interaction-id uuid }}/{{ request-id uuid }}.*
                  or
                 /{{ realm }}/{{ yyyy-MM-dd }}/{{ interaction-id uuid }}/{{ request-id uuid }}/{{ id uuid }}.* "
                 e)
      (throw (ex-info "Unable to parse key"
                      {:key  key
                       :data (ex-data e)})))))

(defn- s3-event?
  [request]
  (= "aws:s3" (get-in request [:Records 0 :eventSource])))

(defn- object-uploaded
  [ctx record]
  (let [key
        (get-in record [:s3 :object :key])

        bucket
        (get-in record [:s3 :bucket :name])]
    (log/info "Parsing key" key)
    (if (string/ends-with? key "/")
      {:skip true}
      (let [{:keys [request-id interaction-id date realm id] :as parsed-key}
            (parse-key key)]
        (log/info "Parsing success " parsed-key)
        {:request-id request-id
         :interaction-id interaction-id
         :user (name (:service-name ctx))
         :meta {:realm (keyword realm)
                :user {:id request-id
                       :email "non-interractiva@s3.amazonws.com"
                       :role :non-interactive}}
         :commands [{:cmd-id :object-uploaded
                     :id id
                     :body (s3/get-object ctx record)
                     :date date
                     :bucket bucket
                     :key key}]}))))

(defn from-bucket
  "Runtime filter turning an S3 object notification (directly or through
   SQS) into an :object-uploaded command, run as the service's
   non-interactive user. Keys are {realm}/{yyyy-MM-dd}/{interaction-id}/{request-id}[/{id}].*"
  [ctx request chain]
  (if (s3-event? request)
    (let [service-ctx
          (assoc ctx :user {:id (name (:service-name ctx))
                            :role :non-interactive})]
      (chain service-ctx (object-uploaded service-ctx (first (:Records request)))))
    (chain ctx request)))
