(ns edd.view-store.common
  (:require [malli.core :as m]
            [malli.error :as me]))

(defn get-type
  [ctx]
  (get-in ctx [:view-store :type]))

(defn validate-impl
  [impl supported]
  (when-not (map? supported)
    (throw (ex-info "Parameter error"
                    {:message  "Supported implementations need to be map. Usually containing :mock :main"
                     :provided supported})))
  (when-not (some #(= % impl) (keys supported))
    (throw (ex-info (str "Unknown implementation: '" impl "' " supported)
                    {:message   "Unknown view-store implementation"
                     :value     impl
                     :supported supported})))
  (when-not (get supported impl)
    (throw (ex-info (str "Unsupported implementation: '" impl "' " supported)
                    {:message   "Unknown view-store implementation"
                     :value     impl
                     :supported supported}))))

(defmulti get-snapshot (fn [ctx _id & [_version]] (get-type ctx)))
(defmulti update-snapshot (fn [ctx _aggregate] (get-type ctx)))
(defmulti with-init (fn [ctx _body-fn] (get-type ctx)))

(defn register-fn
  [ctx & {:keys [config-schema
                 config
                 default-config
                 available-implementations
                 default-implementation
                 implementation]}]
  (let [implementation (or implementation
                           default-implementation)]
    (validate-impl implementation available-implementations)
    (let [config (merge default-config
                        config)]
      (when (not (m/validate config-schema config))
        (let [explain (-> (m/explain config-schema config)
                          (me/humanize))]
          (throw (ex-info (str "Error registering S3 view-store" explain)
                          {:error explain}))))
      (assoc ctx :view-store {:type   (get available-implementations
                                           (or implementation
                                               default-implementation))
                              :config config}))))