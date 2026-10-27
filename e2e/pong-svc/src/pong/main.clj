(ns pong.main
  (:gen-class)
  (:require [edd.core :as edd]
            [edd.dynamodb.event-store :as event-store]
            [edd.el.ctx :as el-ctx]
            [edd.java-lambda-runtime.core :as runtime]
            [edd.response.s3 :as response-cache]
            [edd.s3.view-store :as view-store]
            [edd.sqs.distributor :as distributor]
            [pong.service :as service]))

;; pong-svc has the S3 response cache with test thresholds, 1 KiB per message
;; and 3 effects per part, so the e2e suite covers cached and split
;; responses; ping-svc has no cache and sends its responses inline.
(defn- with-response-cache
  [ctx]
  (response-cache/register (el-ctx/set-effect-partition-size ctx 3)))

(runtime/start
 (service/init (with-response-cache (view-store/register (event-store/register (distributor/register {} {:max-message-size 1024})))))
 edd/handler
 :filters service/request-filters)
