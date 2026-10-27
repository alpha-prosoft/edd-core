(ns edd.response.s3
  "S3 response cache (edd.response.cache): command responses are stored in
   the {AccountId}-{EnvironmentNameLower}-sqs bucket under
   response/{request-id}/{breadcrumbs}/{service}[-part.{n}].json and the
   router messages carry {:s3 {:bucket {:name ..} :object {:key ..}}}."
  (:require [aws.ctx :as aws-ctx]
            [clojure.string :as str]
            [edd.response.cache :as cache]
            [lambda.util :as util]
            [sdk.aws.s3 :as s3]))

(defn- reference
  [{:keys [aws service-name request-id breadcrumbs] :as ctx} idx]
  {:s3 {:bucket {:name (str (:account-id aws) "-" (:environment-name-lower ctx) "-sqs")}
        :object {:key (str "response/"
                           request-id
                           "/"
                           (str/join "-" breadcrumbs)
                           "/"
                           (name service-name)
                           (when idx
                             (str "-part." idx))
                           ".json")}}})

(defn- store
  [ctx {:keys [idx service resp]}]
  (util/d-time
   (str "Storing cache response " (or idx ""))
   (let [part-reference
         (reference ctx idx)

         {:keys [error]}
         (s3/put-object ctx (assoc-in part-reference
                                      [:s3 :object :content]
                                      (util/to-json {:resp resp
                                                     :service service})))]
     (when error
       (throw (ex-info "Error caching response" {:error error})))
     part-reference)))

(defn fetch-part
  "The cached part an S3 reference points to."
  [ctx part-reference]
  (let [{:keys [error] :as content}
        (s3/get-object ctx part-reference)]
    (when error
      (throw (ex-info "Unable to fetch parked message" {:error error})))
    (util/to-edn (slurp content))))

(defn register
  "Caches responses that do not fit one router message in S3 (see
   edd.response.cache); the thresholds are the effect partition size
   (edd.el.ctx/set-effect-partition-size) and the transport message size
   (SqsMaxMessageSize)."
  [ctx]
  (cache/register (aws-ctx/register ctx)
                  {:store store
                   :reference? #(contains? % :s3)
                   :fetch fetch-part}))
