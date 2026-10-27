(ns aws.aws
  (:require
   [lambda.util :as util]))

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)

(defn get-next-invocation
  [runtime-api]
  (util/http-get
   (str "http://" runtime-api "/2018-06-01/runtime/invocation/next")
   {:timeout 90000000}))

(defn get-next-request [runtime-api]
  (let [req (get-next-invocation runtime-api)]
    (if (-> req :body :isBase64Encoded)
      (update-in req [:body :body] util/base64decode)
      req)))

(defn send-loop-runtime-success
  [{:keys [api
           invocation-id]
    :as _ctx}
   response]
  (util/http-post
   (str "http://" api "/2018-06-01/runtime/invocation/" invocation-id "/response")
   {:body (util/to-json response)}))

(defn send-loop-runtime-error
  [{:keys [api
           invocation-id]
    :as _ctx}
   response]
  (util/http-post
   (str "http://" api "/2018-06-01/runtime/invocation/" invocation-id "/error")
   {:body (util/to-json response)}))
