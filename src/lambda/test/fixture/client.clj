(ns lambda.test.fixture.client
  (:require [clojure.test :refer :all]
            [lambda.util :as util]
            [clojure.tools.logging :as log]
            [clojure.data :refer [diff]]
            [org.httpkit.client :as http]))

(def ^:dynamic *world*)

(defn map-body-to-edn
  [traffic]
  (if (:body traffic)
    (try (update traffic :body util/to-edn)
         (catch Exception _e
           traffic))
    traffic))

(defmacro verify-traffic-edn
  [y]
  `(is (= ~y
          (->> (:traffic @*world*)
               (mapv map-body-to-edn)
               (mapv #(dissoc % :keepalive))))))

(defmacro verify-traffic
  [y]
  `(is (= ~y
          (mapv
           #(dissoc % :keepalive)
           (:traffic @*world*)))))

(defn traffic
  ([]
   (mapv map-body-to-edn (:traffic @*world*)))
  ([n]
   (nth (traffic) n)))

(defn responses []
  (map :body (traffic)))

(defn record-traffic
  [req]
  (let [clean-req (if (= (:method req)
                         :get)
                    (dissoc req :req)
                    req)]
    (swap! *world*
           #(update % :traffic
                    (fn [v]
                      (conj v clean-req))))))

(defn remove-at
  [coll idx]
  (vec (concat (subvec coll 0 idx)
               (subvec coll (inc idx)))))

(defn find-first
  [coll func]
  (first
   (keep-indexed (fn [idx v]
                   (if (func v) idx))
                 coll)))

(defn is-match
  [{:keys [url method body]} v]
  (and
   (= (get v method) url)
   (or (= (:req v) nil)
       (= (get v :req) body)
        ; We want :req to be subset of expected body
       (= (first
           (diff (get v :req)
                 (util/to-edn body)))
          nil))))

(defn handle-request
  "Each request contained :method :url pair and response :body.
  Optionally there might be :req which is body of request that
  has to me matched"
  [ctx {:keys [url method] :as req} & rest]
  (record-traffic req)
  (let [all (:responses @*world*)
        idx (find-first
             all
             (partial is-match req))
        resp (get all idx)
        config (:http-mock ctx)]
    (log/debug "CONFIG" config)
    (cond
      idx (do
            (swap! *world*
                   update-in [:responses]
                   #(remove-at % idx))

            (ref
             (dissoc resp method :req :keep)))
      (:ignore-missing config) (do
                                 (log/error {:error {:message "Mock not Found"
                                                     :url     url
                                                     :method  method
                                                     :req     req}})
                                 (ref
                                  {:status 200
                                   :body   (util/to-json {:result nil})}))
      :else (apply (:original-handler ctx)
                   (into [req] rest)))))

(defmacro mock-http-ctx
  [ctx responses & body]
  `(let [responses# ~responses
         ctx# (update ~ctx
                      :http-mock
                      #(merge {:ignore-missing true} %))]
     (binding [*world* (atom {:responses responses#})]
       (let [original-request# http/request]
         (with-redefs [http/request (partial
                                     handle-request
                                     (assoc ctx#
                                            :original-handler original-request#))]
           (do ~@body))))))

(defmacro mock-http
  [responses & body]
  `(let [responses# ~responses
         ctx# {:http-mock {:ignore-missing true}}]
     (binding [*world* (atom {:responses responses#})]
       (let [original-request# http/request]
         (with-redefs [http/request (partial
                                     handle-request
                                     (assoc ctx#
                                            :original-handler original-request#))]
           (do ~@body))))))
