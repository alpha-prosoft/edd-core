(ns lambda.filters
  (:require [lambda.util :as util]
            [clojure.tools.logging :as log]
            [clojure.string :as str]
            [sdk.aws.s3 :as s3]
            [lambda.jwt :as jwt]
            [lambda.uuid :as uuid]))

(def from-queue
  {:condition (fn [{:keys [body]}]
                (if (and
                     (contains? body :Records)
                     (= (:eventSource (first (:Records body))) "aws:sqs"))
                  true
                  false))
   :handler   (fn [{:keys [body] :as ctx} filter-chain]
                (let [ctx (assoc ctx
                                 :body
                                 (mapv
                                  #(util/to-edn (:body %))
                                  (:Records body)))]
                  (apply filter-chain [ctx])))})

(defn parse-key
  [key]
  (try
    (let [parts (str/split key #"/")
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
                   (str/split #"\.")
                   (first)))
          request-id (if (= 2 (count parts))
                       (-> parts
                           (second)
                           (str/split #"\.")
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

(def from-bucket
  {:condition (fn [{:keys [body]}]
                (when (and (vector? body)
                           (> (count body) 1))
                  (throw (ex-info "Unknown constalation"
                                  {:message "I dont know how you could have vector here since s3 is not batching
                                             triggers."})))
                (let [body (if (vector? body)
                             (first body)
                             body)]
                  (and
                   (contains? body :Records)
                   (= (:eventSource (first (:Records body))) "aws:s3"))))
   :handler   (fn [{:keys [body] :as ctx} filter-chain]
                (let [body (if (vector? body)
                             (first body)
                             body)
                      ctx (-> ctx
                              (assoc-in [:user :id] (name (:service-name ctx)))
                              (assoc-in [:user :role] :non-interactive)
                              (assoc :body
                                     (let [record (first (:Records body))
                                           key (get-in record [:s3 :object :key])
                                           bucket (get-in record [:s3 :bucket :name])]
                                       (log/info "Parsing key" key)
                                       (if-not (str/ends-with? key "/")
                                         (let [{:keys [request-id
                                                       interaction-id
                                                       date
                                                       realm
                                                       id] :as parsed-key} (parse-key key)]
                                           (log/info "Parsing success " parsed-key)
                                           {:request-id     request-id
                                            :interaction-id interaction-id
                                            :user           (name (:service-name ctx))
                                            :meta           {:realm (keyword realm)
                                                             :user  {:id    request-id
                                                                     :email "non-interractiva@s3.amazonws.com"
                                                                     :role  :non-interactive}}
                                            :commands       [{:cmd-id :object-uploaded
                                                              :id     id
                                                              :body   (s3/get-object ctx record)
                                                              :date   date
                                                              :bucket bucket
                                                              :key    key}]})
                                         {:skip true}))))]
                  (apply filter-chain [ctx])))})

(defn has-role?
  [user role]
  (some #(= role %)
        (get user :roles [])))

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

(defn extract-user
  [ctx claims]
  (let [groups (:cognito:groups claims)
        groups (cond
                 (vector? groups) groups
                 groups (-> groups
                            (str/split #","))
                 :else [])
        groups (filter #(str/includes? % "-") groups)
        id-claim (keyword (get-in ctx [:auth :mapping :id] :email))
        id (get claims id-claim)]
    (reduce
     (fn [user group]
       (let [group (name group)
             [prefix value] (-> group
                                (str/split #"-" 2))
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
  (fn [{:keys [request] :as _ctx}]
    (get-in request [:requestContext :authorizer :claims :token_use]
            (get-in request [:requestContext :authorizer :token_use]))))

(defmethod check-user-role "id"
  [{:keys [request] :as _ctx}]
  (get-in request [:requestContext :authorizer :claims]))

(defmethod check-user-role "m2m"
  [{:keys [request] :as _ctx}]
  (get-in request [:requestContext :authorizer]))

(defmethod check-user-role :default
  [{:keys [request] :as ctx}]
  (jwt/parse-token
   ctx
   (or (get-in request [:headers :x-authorization])
       (get-in request [:headers :X-Authorization]))))

(defn extract-attrs
  [user-claims]
  (reduce
   (fn [p [k v]]
     (let [key (str/replace (name k) #"department_code" "department-code")
           key (cond
                 (= key "department") key
                 (= key "department-code") key
                 (str/starts-with? key "x-") (subs key 2)
                 (str/starts-with? key "custom:x-") (subs key 9)
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
                      (not (non-interactive user)))
               (throw (ex-info "Selecting non-existing role"
                               {:message       "Selecting non-existing role"
                                :selected-role selected-role
                                :roles         roles}))
               selected-role)
        role (or role
                 (non-interactive user)
                 (first (remove
                         #(or (= % :anonymous)
                              (str/starts-with? (name %)
                                                "realm-"))
                         roles)))
        role (or role :anonymous)

        user (cond-> (merge user {:role role})
               realm (assoc :realm realm))]
    (if (empty? attrs)
      user
      (assoc user :attrs attrs))))

(defn assign-metadata
  [{:keys [body] :as ctx}]
  (let [{:keys [meta] :or {}} body
        {:keys [error] :as user-claims} (check-user-role ctx)
        user-claims (if error
                      (throw (ex-info "User authentication error" error))
                      user-claims)
        {:keys [role] :as user} (parse-authorizer-user ctx user-claims)]
    (if (= :non-interactive
           role)
      (assoc ctx :meta meta)
      (assoc ctx :meta (assoc meta
                              :realm (get-realm ctx user {})
                              :user (dissoc user :realm))))))

(defn create-headers
  [content-type]
  {:Access-Control-Allow-Headers  (str/join
                                   ","
                                   ["Id" "VersionId" "X-Authorization" "Content-Type"
                                    "X-Amz-Date" "Authorization" "X-Api-Key" "X-Amz-Security-Token"])
   :Access-Control-Allow-Methods  "OPTIONS,POST,PUT,GET"
   :Access-Control-Expose-Headers "*"
   :Content-Type                  content-type
   :Access-Control-Allow-Origin   "*"})

(def default-headers (create-headers "application/json"))

(defn from-api-handler
  [{:keys [body request resp-content-type resp-serializer-fn]
    :or   {resp-content-type  "application/json"
           resp-serializer-fn util/to-json}
    :as   ctx}
   filter-chain]
  (let [{http-method :httpMethod
         path        :path
         isBase64Encoded :isBase64Encoded} request
        resp (cond
               (= path "/health") {:healthy  true
                                   :build-id (util/get-env "BuildId" "b0")}

               (= http-method "OPTIONS") {:healthy  true
                                          :build-id (util/get-env "BuildId" "b0")}

               :else (apply filter-chain [(-> ctx
                                              (assoc :body (cond-> (:body body)
                                                             isBase64Encoded util/base64decode
                                                             :always util/to-edn))
                                              (assign-metadata))]))]
    {:statusCode      200
     :isBase64Encoded false
     :headers        (create-headers resp-content-type)
     :body           (resp-serializer-fn resp)}))

(def from-api
  {:init jwt/fetch-jwks-keys
   :condition (fn [{:keys [body]}]
                (contains? body :path))
   :handler   from-api-handler})

(defn to-api
  [_ctx]
  (log/warn "Response filter should not be used any more"))
