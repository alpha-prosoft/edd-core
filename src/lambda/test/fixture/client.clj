(ns lambda.test.fixture.client
  (:require [clojure.test :refer [deftest testing is]]
            [malli.core :as m]
            [malli.error :as me]
            [lambda.util :as util]
            [clojure.tools.logging :as log]
            [clojure.data :refer [diff]]
            [org.httpkit.client :as http]))

(def ^:dynamic *world*)

(defn- body-to-edn
  [body]
  (if  (map? body)
    (if (:body body)
      (update body :body body-to-edn)
      body)
    (try (let [body (util/to-edn body)]
           (if (:body body)
             (update body :body body-to-edn)
             body))
         (catch Exception _e
           body))))

(defn- map-body-to-edn
  [traffic]
  (if (:body traffic)
    (update traffic :body body-to-edn)
    traffic))

(defn cleanup-traffic
  [t]
  (mapv #(dissoc % :keepalive) t))

(defn try-parse-traffic-to-edn
  [traffic]
  (->> traffic
       (mapv map-body-to-edn)
       cleanup-traffic))

(defn traffic-edn
  []
  (try-parse-traffic-to-edn (:recorded-traffic @*world*)))

(defn traffic
  ([]
   (-> (:recorded-traffic @*world*)
       cleanup-traffic))
  ([n]
   (nth (traffic) n)))

(defmacro verify-traffic
  [y]
  `(is (= ~y
          (traffic))))

(defmacro verify-traffic-edn
  [y]
  `(is (= ~y
          (traffic-edn))))

(defn record-traffic
  [req]
  (let [clean-req (if (= (:method req)
                         :get)
                    (dissoc req :req)
                    req)]
    (swap! *world*
           #(update % :recorded-traffic
                    (fn [v]
                      (conj v clean-req))))))

(defn is-match
  [mock-body request-body]
  (let [mock-body (try
                    (util/to-edn mock-body)
                    (catch Exception _e
                      mock-body))
        request-body (try
                       (util/to-edn request-body)
                       (catch Exception _e
                         request-body))]
    (nil?
     (second
      (diff request-body mock-body)))))

(defn handle-request
  "Each request contained :method :url pair and response :body.
  Optionally there might be :req which is body of request that
  has to me matched"
  [ctx {:keys [url method body] :as req} & rest]
  (record-traffic req)
  (let [config (:http-mock ctx)
        traffic (:mock-traffic @*world*)
        mathing-method-url (get-in traffic [url method] {})
        [idx match] (first
                     (filter
                      (fn [[_idx {:keys [request]}]]
                        (is-match
                         (:body request)
                         body))
                      mathing-method-url))]
    (log/debug "CONFIG" config)
    (cond
      idx (do
            (swap! *world*
                   update-in
                   [:mock-traffic url method]
                   #(dissoc % idx))

            (ref (merge {:status 200}
                        (:response match))))
      (not
       (:ignore-missing config)) (do
                                   (log/error {:error
                                               (assoc req
                                                      :message "Mock not Found")})
                                   (ref
                                    {:status 200
                                     :body   (util/to-json {:result nil})}))
      :else (do
              (log/error "Unable to fetch mock so forwarding to original handler"
                         req)
              (apply (:original-handler ctx)
                     (into [req] rest))))))

(def TrafficSchema
  (m/schema
   [:vector
    [:map
     [:method [:enum :get :post :put :delete]]
     [:url [:string {:min 1}]]
     [:response
      [:map
       [:body
        [:any]]]]]]))

(defn setup-traffic
  [mock-traffic]
  (when-not (m/validate TrafficSchema mock-traffic)
    (throw (ex-info "Traffic not matching schema"
                    {:error (-> (m/explain TrafficSchema mock-traffic)
                                me/humanize)})))
  (reduce-kv
   (fn [p idx {:keys [url method] :as item}]
     (assoc-in p [url method idx] item))
   {}
   mock-traffic))

(defmacro mock-http
  [ctx mock-traffic & body]
  `(let [_#  (when-not (map? ~ctx)
               (throw (ex-info "First argument should be map ctx"
                               {:message "Wrong argument type for ctx"
                                :current (type ~ctx)})))
         mock-traffic# (lambda.test.fixture.client/setup-traffic ~mock-traffic)
         ctx# (update ~ctx
                      :http-mock
                      #(merge {:ignore-missing true} %))]

     (binding [*world* (atom {:mock-traffic mock-traffic#})]
       (let [original-request# http/request]
         (with-redefs [http/request (partial
                                     handle-request
                                     (assoc ctx#
                                            :original-handler original-request#))]
           (do ~@body))))))
