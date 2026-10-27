(ns lambda.core
  "Starts a service on the runtime named by :edd/runtime or -Dedd.runtime,
   e.g. aws.lambda/lambda-custom-runtime or edd.undertow.core/start."
  (:gen-class)
  (:require [lambda.util :as util]
            [clojure.tools.logging :as log]))

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)

(defn- runtime-name
  [ctx]
  (or (:edd/runtime ctx)
      (System/getProperty "edd.runtime")
      (throw (ex-info "No runtime configured; set :edd/runtime or -Dedd.runtime, e.g. edd.undertow.core/start"
                      {}))))

(defn- resolve-runtime
  [runtime-name]
  (let [runtime
        (try
          (requiring-resolve (symbol runtime-name))
          (catch java.io.FileNotFoundException e
            (throw (ex-info (str "Unable to load runtime " runtime-name ": " (ex-message e))
                            {:runtime runtime-name}
                            e))))]
    (or runtime
        (throw (ex-info (str "Runtime " runtime-name " does not exist")
                        {:runtime runtime-name})))))

(defn- log-startup-time
  []
  (let [startup-millis
        (Long/parseLong (str (util/get-property "edd.startup-milis" 0)))]
    (log/info "Server started: " (- (System/currentTimeMillis) startup-millis))))

(defn start
  "Runs the configured runtime with ctx and params, usually
   handler & opts. The handler is (fn [ctx request] response): edd.core/handler
   for an edd service or your own fn."
  [ctx & params]
  (try
    (log-startup-time)
    (let [runtime-name
          (runtime-name ctx)

          runtime
          (resolve-runtime runtime-name)]
      (log/info "Runtime: " runtime-name)
      (apply runtime ctx params))
    (catch Exception ex
      ;; println: the init error may come from the logger itself
      (println "Error starting runtime. Potentially Init Phase error" ex)
      (throw ex))))
