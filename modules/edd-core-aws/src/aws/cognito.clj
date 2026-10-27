(ns aws.cognito
  "Cognito for HTTP requests (lambda.http) and service-to-service calls:
   register makes the API filter verify the token (JWKS) and read realm,
   roles and attributes from the Cognito claims or the API Gateway
   authorizer; credentials authorize remote deps with the service's
   Cognito token."
  (:require [aws.ctx :as aws-ctx]
            [clojure.string :as string]
            [edd.core :as edd]
            [lambda.util :as util]
            [sdk.aws.cognito-idp :as cognito-idp]
            [lambda.http :as http]
            [lambda.jwt :as jwt]))

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)

(defn extract-user
  [ctx claims]
  (let [groups (:cognito:groups claims)
        groups (cond
                 (vector? groups) groups
                 groups (-> groups
                            (string/split #","))
                 :else [])
        groups (filter #(string/includes? % "-") groups)
        id-claim (keyword (get-in ctx [:auth :mapping :id] :email))
        id (get claims id-claim)]
    (reduce
     (fn [user group]
       (let [group (name group)
             [prefix value] (-> group
                                (string/split #"-" 2))
             [prefix value] (if (= prefix "lime")
                              ["roles" group]
                              [prefix value])
             [prefix value] (if (= group "non-interactive")
                              ["roles" group]
                              [prefix value])]
         (if (= prefix "realm")
           (assoc user :realm (keyword value))
           (update user (keyword prefix) conj (keyword value)))))
     {:id    id
      :roles '()
      :email (:email claims)}
     groups)))

(defmulti check-user-role
  (fn [ctx]
    (get-in (:req ctx) [:requestContext :authorizer :claims :token_use]
            (get-in (:req ctx) [:requestContext :authorizer :token_use]))))

(defmethod check-user-role "id" [ctx]
  (get-in ctx [:req :requestContext :authorizer :claims]))

(defmethod check-user-role "m2m" [ctx]
  (get-in ctx [:req :requestContext :authorizer]))

(defmethod check-user-role :default
  [{:keys [req] :as ctx}]
  (jwt/parse-token
   ctx
   (or (get-in req [:headers :x-authorization])
       (get-in req [:headers :X-Authorization]))))

(defn extract-attrs
  [user-claims]
  (reduce
   (fn [p [k v]]
     (let [key (string/replace (name k) #"department_code" "department-code")
           key (cond
                 (= key "department") key
                 (= key "department-code") key
                 (string/starts-with? key "x-") (subs key 2)
                 (string/starts-with? key "custom:x-") (subs key 9)
                 :else nil)]
       (if key
         (assoc p (keyword key) v)
         p)))
   {}
   user-claims))

(defn parse-authorizer-user
  [{:keys [body] :as ctx} user-claims]
  (let [{:keys [roles realm] :as user} (extract-user ctx user-claims)
        attrs (extract-attrs user-claims)
        selected-role (-> body :user :selected-role)
        role (if (and selected-role
                      (not-any? #(= % selected-role)
                                roles)
                      (not (http/non-interactive user)))
               (throw (ex-info "Selecting non-existing role"
                               {:message       "Selecting non-existing role"
                                :selected-role selected-role
                                :roles         roles}))
               selected-role)
        role (or role
                 (http/non-interactive user)
                 (first (remove
                         #(or (= % :anonymous)
                              (string/starts-with? (name %)
                                                   "realm-"))
                         roles)))
        role (or role :anonymous)

        user (cond-> (merge user {:role role})
               realm (assoc :realm realm))]
    (if (empty? attrs)
      user
      (assoc user :attrs attrs))))

(defn resolve-user
  [ctx]
  (let [{:keys [error] :as user-claims}
        (check-user-role ctx)]
    (when error
      (throw (ex-info "User authentication error" error)))
    (parse-authorizer-user ctx user-claims)))

(defn register
  "API users from Cognito tokens."
  [ctx]
  (edd/reg-feature (aws-ctx/register ctx) :edd/cognito
                   :deps {}
                   :init jwt/fetch-jwks-keys
                   :resolve-user resolve-user))

(def ^:private refresh-before-ms
  (* 5 60 1000))

(defn- fetch-token
  [ctx]
  (let [{:keys [error] :as response}
        (cognito-idp/admin-initiate-auth ctx)

        {:keys [IdToken ExpiresIn]}
        (:AuthenticationResult response)]
    (when error
      (throw (ex-info "Unable to get a Cognito token" {:error error})))
    {:token IdToken
     :expires-at (+ (long (util/get-current-time-ms))
                    (* 1000 (long (or ExpiresIn 3600)))
                    (- (long refresh-before-ms)))}))

(defn- current-token
  [ctx cache]
  (let [{:keys [token expires-at]}
        @cache]
    (if (and token
             (< (long (util/get-current-time-ms)) (long expires-at)))
      token
      (:token (reset! cache (fetch-token ctx))))))

(defn credentials
  "Credentials provider of the HTTP deps resolver (edd.client.deps): the
   service's Cognito id token, cached until shortly before it expires.
   A ctx :id-token takes precedence."
  []
  {:init (fn [_ctx]
           (atom nil))
   :authorize (fn [ctx cache request]
                (assoc-in request
                          [:headers "X-Authorization"]
                          (or (:id-token ctx)
                              (current-token ctx cache))))})
