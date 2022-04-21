(ns edd.dal
  (:require [clojure.tools.logging :as log]))

(defmulti get-events
  (fn [{:keys [_id] :as ctx}]
    (:edd-event-store ctx :default)))

(defmethod get-events
  :default
  [_] [])

(defmulti get-sequence-number-for-id
  (fn [{:keys [_id] :as ctx}]
    (:edd-event-store ctx)))

(defmulti get-id-for-sequence-number
  (fn [{:keys [_sequence] :as ctx}]
    (:edd-event-store ctx)))

(defmulti get-aggregate-id-by-identity
  (fn [{:keys [_identity] :as ctx}]
    (:edd-event-store ctx)))

(defmulti store-effects
  (fn [ctx] (:edd-event-store ctx)))

(defmulti log-dps
  (fn [ctx] (:edd-event-store ctx)))

(defmulti log-request
  (fn [{:keys [_commands] :as ctx} _body]
    (:edd-event-store ctx)))

(defmulti log-request-error
  (fn [{:keys [_commands] :as ctx} _body _error]
    (:edd-event-store ctx)))

(defmulti log-response
  (fn [ctx]
    (:edd-event-store ctx)))

(defmulti get-max-event-seq
  (fn [{:keys [_id] :as ctx}]
    (:edd-event-store ctx)))

(defmulti get-command-response
  (fn [{:keys [_request-id _breadcrumbs] :as ctx}]
    (:edd-event-store ctx)))

(defmulti store-results
  (fn [ctx] (:edd-event-store ctx)))

(defmulti get-records
  (fn [ctx _query] (:edd-event-store ctx)))

(defmulti with-init
  (fn [ctx _body-fn] (:edd-event-store ctx)))

(defmethod with-init
  :default
  [ctx body-fn]
  (log/info "Default event init")
  (body-fn ctx))
