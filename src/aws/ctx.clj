(ns aws.ctx
  (:require [lambda.util :as util]
            [clojure.tools.logging :as log]
            [malli.core :as m]
            [malli.error :as me]
            [clojure.string :as string]))

(def AWSRuntimeSchema
  (m/schema
   [:map
    [:region string?]
    [:account-id string?]
    [:aws-access-key-id string?]
    [:aws-secret-access-key string?]
    [:aws-session-token {:optional true} string?]]))

(defn- fetch-creds-from-uri [uri token]
  (try
    (log/info "Fetching credentials from URI:" uri)
    (let [headers (when token {"Authorization" token})
          {:keys [body]}
          (util/http-request uri {:method :get
                                  :headers headers})]
      (if (and (:AccessKeyId body)
               (:SecretAccessKey body))

        {:aws-access-key-id
         (:AccessKeyId body)

         :aws-secret-access-key
         (:SecretAccessKey body)

         :aws-session-token
         (or (:Token body) "")}
        (do
          (log/warn "Fetched incomplete credentials from URI"
                    {:uri uri :response-keys (keys body)})
          nil)))
    (catch Exception e
      (log/warn "Failed to fetch credentials from URI"
                {:uri uri :exception (ex-message e)})
      nil)))

(defn- fetch-creds-from-imds []
  (try
    (log/info "Attempting to fetch credentials from EC2 instance metadata service.")
    (let [base-url "http://169.254.169.254/latest/meta-data/iam/security-credentials/"
          role-name (try (util/http-get base-url {:as :text :timeout 1000})
                         (catch Exception _ nil))] ; Return nil if role name fetch fails
      (if (string/blank? role-name)
        (do
          (log/info "No IAM role found from instance metadata service (or service not available).")
          nil)
        (let [creds-url (str base-url (string/trim role-name))
              response (util/http-get creds-url {:as :json :timeout 1000})]
          (if (and (= "Success" (:Code response)) (:AccessKeyId response) (:SecretAccessKey response))
            {:aws-access-key-id     (:AccessKeyId response)
             :aws-secret-access-key (:SecretAccessKey response)
             :aws-session-token     (or (:Token response) "")}
            (do
              (log/warn "Fetched incomplete or unsuccessful credentials from EC2 instance metadata"
                        {:url creds-url :response-code (:Code response) :response-keys (keys response)})
              nil)))))
    (catch Exception e
      (log/info "Failed to fetch credentials from EC2 instance metadata service (this is expected if not running on EC2):"
                {:exception (ex-message e)})
      nil)))

(defn- resolve-credentials
  [preloaded-creds]
  (let [direct-env-key (util/get-env "AWS_ACCESS_KEY_ID")
        direct-env-secret (util/get-env "AWS_SECRET_ACCESS_KEY")
        container-uri (util/get-env "AWS_CONTAINER_CREDENTIALS_FULL_URI")
        container-token (util/get-env "AWS_CONTAINER_AUTHORIZATION_TOKEN")]
    (cond
      (:aws-access-key-id preloaded-creds)
      (do (log/info "Using preloaded AWS credentials from context.")
          preloaded-creds)

      (and direct-env-key
           direct-env-secret)
      (do (log/info "Using AWS credentials from direct environment variables (AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY).")
          {:aws-access-key-id     direct-env-key
           :aws-secret-access-key direct-env-secret
           :aws-session-token     (util/get-env "AWS_SESSION_TOKEN" "")})

      (some? container-uri)
      (do (log/info "AWS_CONTAINER_CREDENTIALS_FULL_URI is set, attempting to fetch credentials.")
          (or (fetch-creds-from-uri container-uri container-token)
              (do (log/warn "Failed to retrieve credentials from AWS_CONTAINER_CREDENTIALS_FULL_URI!!.")
                  nil)))

      (and (nil? container-uri) (nil? container-token))
      (do (log/info "Attempting to fetch credentials from IMDS.")
          (or (fetch-creds-from-imds)
              (do (log/info "Failed to retrieve credentials from IMDS. Continuing without these credentials.")
                  nil)))

      :else
      (do (log/warn "No specific AWS credential provider matched!!.")
          nil))))

(defn init
  [ctx]
  (log/info "Initializing AWS context")
  (let [preloaded-aws-config
        (get ctx :aws {})

        resolved-creds
        (resolve-credentials preloaded-aws-config)

        aws (merge {:region                (util/get-env "Region"
                                                         (util/get-env "AWS_DEFAULT_REGION" "local"))
                    :account-id            (util/get-env "AccountId" "local")
                    :aws-access-key-id     ""
                    :aws-secret-access-key ""
                    :aws-session-token     ""}
                   resolved-creds
                   preloaded-aws-config)]

    (when-not (m/validate AWSRuntimeSchema aws)
      (throw (ex-info (str "Error initializing aws config. Invalid AWS configuration.")
                      {:error (-> (m/explain AWSRuntimeSchema aws)
                                  (me/humanize))
                       :config aws}))) ; Include the problematic config in the error
    (assoc ctx :aws aws)))
