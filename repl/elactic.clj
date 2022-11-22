
(require '[edd.elastic.elastic-it :as e])
(require '[lambda.elastic :as el])
(require '[lambda.util :as util])
(require '[edd.response.cache :as response-cache])
(require '[edd.view-store.elastic :as elastic-view-store])
(require '[clojure.string :as string])
(require '[aws.ctx :as aws-ctx])

(defn generate
  [ctx bulk-size i]
  (println i)
  (let [data (reduce
              (fn [req idx]
                (let [req (conj
                           req
                           (util/to-json
                            {"index" {"_index" "test" "_id" (str idx)}}))]
                  (conj
                   req
                   (util/to-json
                    (reduce
                     (fn [p v]
                       (assoc p (str v) 100))
                     {:id idx}
                     (range 296))))))
              []
              (range (* i bulk-size) (+ (* i bulk-size) bulk-size)))]

    (el/query
     (merge
      ctx
      {:method         "POST"
       :path           (str "/_bulk")
       :body           (string/join "\n" (conj data "\n"))}))))

(comment
  (let [ctx (e/get-ctx)
        ctx (-> ctx
                (response-cache/register-default)
                (elastic-view-store/register {:scheme "https"
                                              :url   "glms-index-svc.lime-dev01.internal.rbigroup.cloud"})
                (assoc :meta {:realm :test}))
        body {:settings
              {:index
               {:number_of_shards   1
                :number_of_replicas 0}}
              :mappings
              {:dynamic_templates
               [{:integers
                 {:match_mapping_type "long",
                  :mapping
                  {:type "integer",
                   :fields
                   {:number {:type "long"},
                    :keyword
                    {:type         "keyword",
                     :ignore_above 256}}}}}]}}
        bulk-size 10000]
    (el/query
     (merge
      ctx
      {:method         "DELETE"
       :path           (str "/test")
       :body           (util/to-json body)}))
    (el/query
     (merge
      ctx
      {:method         "PUT"
       :path           (str "/test")
       :body           (util/to-json body)}))
    (doall
     (mapv
      (fn [i] (generate ctx bulk-size i))
      (range 30)))

    (println "Done")
    "da"))

(comment
  (let [ctx (e/get-ctx)
        ctx (-> ctx
                (assoc
                 :body {}
                 :aws {:region                "eu-central-1"
                       :account-id            "361331260137"
                       :aws-access-key-id     "ASIAVIIIFPLURUMBLDVP"
                       :aws-secret-access-key "Ga5Vnz2cm9Vzs64ZZojqtVHCDBty2NzsEAS5fTjl"
                       :aws-session-token     "IQoJb3JpZ2luX2VjEKH//////////wEaDGV1LWNlbnRyYWwtMSJGMEQCIHs+OzX0TvrvqcuyFsw9eHeWE/DqTyXCyL+ykAl9DRY5AiB2Cdu4PrJ2N2refMIQd21//VXiiVIhGRqMWrWVnCbsXCr2Awiq//////////8BEAEaDDM2MTMzMTI2MDEzNyIMIi6uAS7PkkXkPRJ1KsoDkimCbv58TuEi6WKLZz0WipFnny5ioLVMl27MZqrVaCJbdbcJNaNvuc/JtHNB/uuhKBL1DrfugfdNn7XnczP8g0aRouzCQmBX5czfX2b8Tvy0Kpd8XVDKGYMf5EicUTKyCBeWuBnrAlAo5Rf5OTircvmi/G5MEmXfFchBNWI5GIPqWWQToTTb35xn4UuKdlpUDmPGt807gYOEMSosRmokxVhs2ICR7Gu/kVoDf9amsbzk72YukHz3nuH8KtUyviu7mb9CLuJIraXk3w0fZ0FPQ+/9PdNaWda0BHU+EUm+ojLx6KPL1Ox+hJ2G0HsF2j5UgiWiaZCF1yaj1vr7jzGRl8fQuL6gNKWB2cAAG1XHKL69kIX3/U9FzLor8vh+Ojp0IgSWtG03IIxCUkh/PQgle/he21X+n7Ll8ntD2/iPGOOD3XZXZFlmhF+a8O4brQeBpY9bwZy4zWxEvy53/CuBAvqxjsaR7SRXb5bBrb62qDx/feCvHhU+7824FuLdwROnQlKuWhsMdw9GSKr/KICcuA5935y8xd379usXOe8eDWfSwt6q39l/roLUR+FgFCf4pqO9Yzb6/0ACzENkE4hWCInaxJXsUOgEqjcw8fbzmwY6hQIutTD/BH4cR0R4WRM2sfJb8FeLEkDunkj/XR9T0m98XETruj16JirEqSX2wt/PO0ff6bPX1HxA+uBXDo7pZm0U0XeGFZJpDw6ktgq27nxAseUKeFCOzsFv0XJzAX3CDw848nDMFrV+7SXs3fIdAcvdRlTBQHCdXat9UWK28o9xl5SQQyfD9m6iaRvYpwEr7F7Ez4Z2oe7U5S63NLB62HwrVJFiJDzdyiLBIBKqVKGhWOMCDFVZ+l8isvRHauiVNTegv8z6AFS87ya9n5IX+P0dAInqQSzPnmOQITpkfH2CehhADe1IYCWJvRU03RtBfAd6AkB6IEDF9USHE7lyuqzOXgTwIt8="})
                (response-cache/register-default)
                (elastic-view-store/register {:config
                                              {:scheme "https"
                                               :url   "glms-index-svc.lime-dev01.internal.rbigroup.cloud"}})
                (assoc :meta {:realm :test}))
        body {:query
              {:term
               {"attrs.cocunut.keyword"
                {"value" "66601"}}}}]
    (clojure.pprint/pprint (util/to-json body))
    (clojure.pprint/pprint
     (el/query
      (merge
       ctx
       {:method         "POST"
        :path           (str "/test_glms_dimension_svc/_search")
        :body           (util/to-json body)})))
    (clojure.pprint/pprint
     (el/query
      (merge
       ctx
       {:method         "GET"
        :path           (str "/test_glms_dimension_svc/_doc/c0000000-0000-0000-0000-000000066601")})))
    (println "Done")
    "da"))
