(ns lambda.test.fixture.core
  (:require [lambda.filters :as fl]
            [lambda.test.fixture.client :as client]
            [lambda.util :as util]))

(def region "eu-central-1")

(def next-url "http://mock/2018-06-01/runtime/invocation/next")

(def ^:dynamic *mocking* false)

(defn get-env-mock
  [base e & [def]]
  (let [sysenv (System/getenv)
        env (merge
             {"AWS_LAMBDA_RUNTIME_API" "mock"
              "Region"                 region}
             base)]
    (get env e (get sysenv e def))))

; TODO Update JWT tokens for this to work properl
(defn realm-mock
  [_ _ _] :test)

(defn number-to-uuid
  [n]
  (str
   "00000000-0000-0000-0000-"
   (format "%012d" n)))

(def created-date "20200426T061823Z")

(def inocation-id-0 #uuid "00000000-0000-0000-0000-000000000000")
(def inocation-id-1 #uuid "00000000-0000-0000-0000-000000000001")

(def response-endpoint-o (str "http://mock/2018-06-01/runtime/invocation/" inocation-id-0 "/response"))
(def response-endpoint-1 (str "http://mock/2018-06-01/runtime/invocation/" inocation-id-1 "/response"))

(def error-endpoint-o (str "http://mock/2018-06-01/runtime/invocation/" inocation-id-0 "/error"))

(def service-name :local-test)
(def ctx
  (-> {:service-name service-name}
      (assoc-in [:edd :config :secrets-file] "files/secret-eu-west.json")))

(defmacro mock-core
  "Usef for testing edd-core commands.
  Aliases: :requests :responses :traffic"
  [ctx & body]
  `(do
     (when lambda.test.fixture.core/*mocking*
       (throw (ex-info "Nested mocking" {:message "Nested mocking not allowed"})))
     (binding [lambda.test.fixture.core/*mocking* true]
       (let [ctx# ~ctx
             responses# (or (:traffic ctx#)
                            (:responses ctx#)
                            (:requests ctx#)
                            [])
             ctx# (update ctx#
                          :http-mock
                          #(merge {:ignore-missing true} %))]
         (with-redefs [lambda.filters/get-realm realm-mock
                       lambda.util/get-env (partial get-env-mock (:env ctx#))
                       lambda.util/get-current-time-ms (fn [] 1587403965)
                       sdk.aws.common/create-date (fn [] (get ctx#
                                                              :created-date
                                                              created-date))]
           (lambda.test.fixture.client/mock-http
            ctx#
            (or responses# [])
            (do
              ~@body)))))))
