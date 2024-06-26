(ns edd.search-it
  (:require [clojure.test :refer :all]
            [clojure.tools.logging :as log]
            [lambda.util :as util]
            [edd.view-store.elastic :as elastic-view-store]
            [edd.view-store.common :as view-store-common]
            [edd.memory.event-store :as memory-event-store]
            [edd.search :as search]
            [lambda.elastic :as el]
            [lambda.uuid :as uuid]
            [clojure.string :as str]
            [aws.ctx :as aws-ctx]
            [edd.test.fixture.dal :as mock]))

(defn ctx
  []
  (let [ctx {:meta {:realm :test}}]
    (if (util/get-env "AWS_ACCESS_KEY_ID")
      (-> {}
          aws-ctx/init)
      ctx)))

(defn load-data
  [ctx]
  (let [path (str "/"
                  (elastic-view-store/realm ctx)
                  "_" (:service-name ctx) "/_doc")]
    (doseq [i (mock/peek-state :aggregate-store)
            {:keys [error]} (el/query
                             {:config (get-in ctx [:view-store :config])
                              :method "POST"
                              :path   path
                              :body   (util/to-json
                                       i)})]
      (when error
        (throw (ex-info "Error loading data" error))))
    (log/info (str "Data loaded: " path))))

(defn test-query
  [data q]
  (binding [memory-event-store/*event-store* (atom {})
            view-store-common/*view-store*  (atom {:aggregate-store data})]
    (let [service-name (str/replace (str (uuid/gen)) "-" "_")
          local-ctx (assoc (ctx) :service-name service-name)
          el-ctx (-> local-ctx
                     (elastic-view-store/register)
                     (assoc :query q))
          body {:settings
                {:index
                 {:number_of_shards   1
                  :number_of_replicas 0}}
                :mappings
                {:dynamic_templates
                 [{:integers
                   {:match_mapping_type "long",
                    :mapping
                    {:type   "integer",
                     :fields {:number  {:type "long"},
                              :keyword {:type         "keyword",
                                        :ignore_above 256}}}}}]}}]

      (log/info "Deleting existing test indices" service-name)
      (let [{:keys [error]} (el/query
                             {:config (get-in el-ctx [:view-store :config])
                              :method "DELETE"
                              :path   "/test*"})]
        (when error
          (throw (ex-info "Deleting indices" error))))

      (log/info "Index name" service-name)
      (let [{:keys [error] :as body} (el/query
                                      {:config (get-in el-ctx [:view-store :config])
                                       :method "PUT"
                                       :path   (str "/"
                                                    (elastic-view-store/realm el-ctx)
                                                    "_"
                                                    service-name)
                                       :body   (util/to-json body)})]
        (when error
          (throw (ex-info "Error creating index"
                          error)))
        (log/info "Index creation response: " body))
      (load-data el-ctx)
      (util/thread-sleep 2000)
      (let [{:keys [error] :as body} (el/query
                                      {:config (get-in el-ctx [:view-store :config])
                                       :method "GET"
                                       :path   (str "/"
                                                    (elastic-view-store/realm el-ctx)
                                                    "_"
                                                    service-name
                                                    "/_mapping")})]
        (when error
          (throw (ex-info "Error getting index mapping"
                          error)))
        (log/info "Mapping: " body))
      (let [el-result (search/advanced-search el-ctx)
            mock-result (search/advanced-search (-> local-ctx
                                                    (elastic-view-store/register
                                                     :implementation :mock)
                                                    (assoc :query q)))]
        (log/info el-result)
        (log/info mock-result)
        (el/query
         {:config (get-in el-ctx [:view-store :config])
          :method "DELETE"
          :path   (str "/" service-name)})
        [el-result mock-result]))))

(deftest test-elastic-mock-parity-1
  (let [data [{:k1 "121" :k2 "be" :k3 {:k31 "c"}}
              {:k1 "758" :k2 "be"}
              {:k1 "121" :k2 "be" :k3 {:k31 "d"}}]
        query {:filter [:and
                        [:and
                         [:eq :k1 "121"]
                         [:eq :k2 "be"]]
                        [:eq "k3.k31" "c"]]}
        expected {:total 1
                  :from  0
                  :size  50
                  :hits  [{:k1 "121" :k2 "be" :k3 {:k31 "c"}}]}
        [el-result mock-result] (test-query data query)]
    (is (= expected
           el-result))
    (is (= expected
           mock-result))))

(deftest test-elastic-mock-parity-2
  (let [data [{:k1 "121" :k2 "be" :k3 {:k31 "c"}}
              {:k1 "758" :k2 "be"}
              {:k1 "751" :k2 "be"}
              {:k1 "751" :k2 "bb"}
              {:k1 "121" :k2 "be" :k3 {:k31 "d"}}]
        query {:filter [:and
                        [:eq :k2 "be"]
                        [:in :k1 ["751" "758"]]]}
        expected {:total 2
                  :from  0
                  :size  50
                  :hits  [{:k1 "758" :k2 "be"}
                          {:k1 "751" :k2 "be"}]}
        [el-result mock-result] (test-query data query)]
    (is (= expected
           el-result))
    (is (= expected
           mock-result))))

(deftest test-elastic-mock-parity-keyword-in-2
  (let [data [{:k1 :v0 :k2 "be" :k3 {:k31 "c"}}
              {:k1 :v1 :k2 "bi"}
              {:k1 :v1 :k2 "be"}
              {:k1 :v2 :k2 "be"}
              {:k1 :v1 :k2 "bb"}
              {:k1 :v3 :k2 "be" :k3 {:k31 "d"}}]
        query {:filter [:and
                        [:eq :k2 "be"]
                        [:in :k1 [:v1 :v2]]]}
        expected {:total 2
                  :from  0
                  :size  50
                  :hits  [{:k1 :v1 :k2 "be"}
                          {:k1 :v2 :k2 "be"}]}
        [el-result mock-result] (test-query data query)]
    (is (= expected
           el-result))
    (is (= expected
           mock-result))))

(deftest test-elastic-mock-parity-3
  (let [data [{:k1 "121" :k2 "be" :k3 {:k31 "c"}}
              {:k1 "758" :k2 "be"}
              {:k1 "751" :k2 "be"}
              {:k1 "751" :k2 "bb"}
              {:k1 "121" :k2 "be" :k3 {:k31 "d"}}]
        query {:filter [:and
                        [:eq :k2 "be"]
                        [:not
                         [:in :k1 ["751" "758"]]]]}
        expected {:total 2
                  :from  0
                  :size  50
                  :hits  [{:k1 "121"
                           :k2 "be"
                           :k3 {:k31 "c"}}
                          {:k1 "121"
                           :k2 "be"
                           :k3 {:k31 "d"}}]}

        [el-result mock-result] (test-query data query)]
    (is (= expected
           el-result))
    (is (= expected
           mock-result))))

(deftest test-elastic-mock-parity-4
  (let [data [{:k1 "121" :attrs {:type :booking-company}}
              {:k1 "122" :attrs {:type :breaking-company}}
              {:k1 "123" :attrs {:type :booking-company}}]
        query {:filter [:not
                        [:eq "attrs.type" :booking-company]]}
        expected
        {:total 1
         :from  0
         :size  50
         :hits  [{:k1 "122" :attrs {:type :breaking-company}}]}
        [el-result mock-result] (test-query data query)]
    (is (= expected
           el-result))
    (is (= expected
           mock-result))))

(deftest test-elastic-mock-parity-test-search-1
  (let [data [{:k1 "consectetur" :attrs {:type :booking-company}}
              {:k1 "lorem" :k2 "1adc" :attrs {:type :breaking-company}}
              {:k1 "ipsum" :k2 "2abc" :attrs {:type :breaking-company}}
              {:k1 "dolor" :k2 "3abc" :attrs {:type :breaking-company}}
              {:k1 "lem" :k2 "4adcor" :attrs {:type :breaking-company}}
              {:k1 "ipsum" :k2 "5abc" :attrs {:type :breaking-company}}
              {:k1 "dol" :k2 "6abc" :attrs {:type :breaking-company}}
              {:k1 "amet" :k2 "7abc" :attrs {:type :booking-company}}]
        query {:search [:fields [:k1 :k2]
                        :value "or"]
               :filter [:not
                        [:eq "attrs.type" :booking-company]]}
        expected {:total 3
                  :from  0
                  :size  50
                  :hits  [{:k1 "lorem" :k2 "1adc" :attrs {:type :breaking-company}}
                          {:k1 "dolor" :k2 "3abc" :attrs {:type :breaking-company}}
                          {:k1 "lem" :k2 "4adcor" :attrs {:type :breaking-company}}]}

        [el-result mock-result] (test-query data query)]
    (is (= expected
           el-result))
    (is (= expected
           mock-result))))

(deftest test-elastic-mock-parity-test-search-only-1
  (let [data [{:k1 "consectetur" :attrs {:type :booking-company}}
              {:k1 "lorem" :k2 "1adc" :attrs {:type :breaking-company}}
              {:k1 "ipsum" :k2 "2abc" :attrs {:type :breaking-company}}
              {:k1 "dolor" :k2 "3abc" :attrs {:type :breaking-company}}
              {:k1 "lem" :k2 "4adcor" :attrs {:type :breaking-company}}
              {:k1 "ipsum" :k2 "5abc" :attrs {:type :breaking-company}}
              {:k1 "dol" :k2 "6abc" :attrs {:type :breaking-company}}
              {:k1 "amet" :k2 "7aor" :attrs {:type :booking-company}}]
        query {:search [:fields [:k1 :k2]
                        :value "or"]}
        expected {:total 4
                  :from  0
                  :size  50
                  :hits  [{:k1 "lorem" :k2 "1adc" :attrs {:type :breaking-company}}
                          {:k1 "dolor" :k2 "3abc" :attrs {:type :breaking-company}}
                          {:k1 "lem" :k2 "4adcor" :attrs {:type :breaking-company}}
                          {:k1 "amet" :k2 "7aor" :attrs {:type :booking-company}}]}
        [el-result mock-result] (test-query data query)]
    (is (= expected
           el-result))
    (is (= expected
           mock-result))))

(deftest test-elastic-mock-parity-earch-only-2
  (let [data [{:k1 "consectetur" :attrs {:type :booking-company}}
              {:k1 "lorem" :k2 "1adc" :attrs {:type :breaking-company}}
              {:k1 "ipsum" :k2 "2abc" :attrs {:type :breaking-company}}
              {:k1 "dolor" :k2 "3abc" :attrs {:type :breaking-company}}
              {:k1 "lem" :k2 "4adcor" :attrs {:type :breaking-company}}
              {:k1 "ipsum" :k2 "5abc" :attrs {:type :breaking-company}}
              {:k1 "dol" :k2 "6abc" :attrs {:type :breaking-company}}
              {:k1 "amet" :k2 "7aor" :attrs {:type :booking-company}}
              {:k1 "ame5" :k2 "7aor" :attrs {:type :booking-company}}]
        query {:search [:fields [:k1 :k2]
                        :value "or"]
               :from   2
               :size   2}
        expected {:total 5
                  :from  2
                  :size  2
                  :hits  [{:k1 "lem" :k2 "4adcor" :attrs {:type :breaking-company}}
                          {:k1 "amet" :k2 "7aor" :attrs {:type :booking-company}}]}
        [el-result mock-result] (test-query data query)]
    (is (= expected
           el-result))
    (is (= expected
           mock-result))))

(deftest test-elastic-mock-parity-test-search-only-3
  (let [data [{:attrs {:top-parent-id #uuid "1111a4b3-1c40-48ec-b6c9-b9cceeea6bb8"
                       :top-gcc-id    #uuid "2222a4b3-1c40-48ec-b6c9-b9cceeea6bb8"}}
              {:attrs {:top-parent-id #uuid "3333a4b3-1c40-48ec-b6c9-b9cceeea6bb8"
                       :top-gcc-id    #uuid "4444a4b3-1c40-48ec-b6c9-b9cceeea6bb8"}}
              {:attrs {:top-parent-id #uuid "5555a4b3-1c40-48ec-b6c9-b9cceeea6bb8"
                       :top-gcc-id    #uuid "2222a4b3-1c40-48ec-b6c9-b9cceeea6bb8"}}
              {:attrs {:top-parent-id #uuid "2222a4b3-1c40-48ec-b6c9-b9cceeea6bb8"
                       :top-gcc-id    #uuid "8888a4b3-1c40-48ec-b6c9-b9cceeea6bb8"}}]
        query {:filter [:or
                        [:eq :attrs.top-parent-id #uuid "1111a4b3-1c40-48ec-b6c9-b9cceeea6bb8"]
                        [:eq :attrs.top-gcc-id #uuid "2222a4b3-1c40-48ec-b6c9-b9cceeea6bb8"]]}
        expected {:total 2
                  :from  0
                  :size  50
                  :hits  [{:attrs {:top-parent-id #uuid "1111a4b3-1c40-48ec-b6c9-b9cceeea6bb8"
                                   :top-gcc-id    #uuid "2222a4b3-1c40-48ec-b6c9-b9cceeea6bb8"}}
                          {:attrs {:top-parent-id #uuid "5555a4b3-1c40-48ec-b6c9-b9cceeea6bb8"
                                   :top-gcc-id    #uuid "2222a4b3-1c40-48ec-b6c9-b9cceeea6bb8"}}]}
        [el-result mock-result] (test-query data query)]
    (is (= expected
           el-result))
    (is (= expected
           mock-result))))

(deftest test-elastic-mock-parity-test-search-4
  (let [data [{:k1 "consectetur" :attrs {:type :booking-company}}
              {:k1 "lorem" :k2 "1adc" :attrs {:type :breaking-company}}
              {:k1 "ipsum" :k2 "2abc" :attrs {:type :breaking-company}}
              {:k1 "dolor" :k2 "3abc" :attrs {:type :breaking-company}}
              {:k1 "lem" :k2 "4adcor" :attrs {:type :breaking-company}}
              {:k1 "ipsum" :k2 "5abc" :attrs {:type :breaking-company}}
              {:k1 "dol" :k2 "6abc" :attrs {:type :breaking-company}}
              {:k1 "amet" :k2 "7abc" :attrs {:type :booking-company}}]
        query {:search [:fields [:k1 :k2]
                        :value "or"]
               :filter [:not
                        [:eq "attrs.type" :booking-company]]}
        expected {:total 3
                  :from  0
                  :size  50
                  :hits  [{:k1 "lorem" :k2 "1adc" :attrs {:type :breaking-company}}
                          {:k1 "dolor" :k2 "3abc" :attrs {:type :breaking-company}}
                          {:k1 "lem" :k2 "4adcor" :attrs {:type :breaking-company}}]}

        [el-result mock-result] (test-query data query)]
    (is (= expected
           el-result))
    (is (= expected
           mock-result))))

(deftest test-elastic-mock-parity-select-filter-2
  (let [data [{:k1 "121" :k2 {:k21 "a" :k22 "1"}}
              {:k1 "758" :k2 {:k21 "c" :k22 "2"}}
              {:k1 "751" :k2 {:k21 "c" :k22 "2"}}
              {:k1 "751" :k2 {:k21 "d" :k22 "2"}}
              {:k1 "121" :k2 {:k21 "e" :k22 "3"}}]
        query {:select ["k1" "k2.k21"]
               :size   50
               :filter [:eq "k2.k22" "2"]}
        expected {:total 3
                  :from  0
                  :size  50
                  :hits  [{:k1 "758" :k2 {:k21 "c"}}
                          {:k1 "751" :k2 {:k21 "c"}}
                          {:k1 "751" :k2 {:k21 "d"}}]}
        [el-result mock-result] (test-query data query)]
    (is (= expected
           el-result))
    (is (= expected
           mock-result))))

(deftest test-elastic-mock-parity-select-filter-keywords
  (let [data [{:k1 "121" :k2 {:k21 "a" :k22 "1"}}
              {:k1 "758" :k2 {:k21 "c" :k22 "2"}}
              {:k1 "751" :k2 {:k21 "c" :k22 "2"}}
              {:k1 "751" :k2 {:k21 "d" :k22 "2"}}
              {:k1 "121" :k2 {:k21 "e" :k22 "3"}}]
        query {:select [:k1 :k2.k21]
               :size   50
               :filter [:eq "k2.k22" "2"]}
        expected {:total 3
                  :from  0
                  :size  50
                  :hits  [{:k1 "758" :k2 {:k21 "c"}}
                          {:k1 "751" :k2 {:k21 "c"}}
                          {:k1 "751" :k2 {:k21 "d"}}]}
        [el-result mock-result] (test-query data query)]
    (is (= expected
           el-result))
    (is (= expected
           mock-result))))

(deftest test-elastic-mock-parity-sort-asc
  (let [data [{:k1 "121" :k2 {:k21 "a" :k22 "1"}}
              {:k1 "758" :k2 {:k21 "c" :k22 "2"}}
              {:k1 "752" :k2 {:k21 "d" :k22 "2"}}
              {:k1 "751" :k2 {:k21 "c" :k22 "2"}}
              {:k1 "121" :k2 {:k21 "e" :k22 "3"}}]
        query {:select [:k1 :k2.k21]
               :size   50
               :filter [:eq "k2.k22" "2"]
               :sort   [:k1 "asc"]}
        expected {:total 3
                  :from  0
                  :size  50
                  :hits  [{:k1 "751" :k2 {:k21 "c"}}
                          {:k1 "752" :k2 {:k21 "d"}}
                          {:k1 "758" :k2 {:k21 "c"}}]}
        [el-result mock-result] (test-query data query)]
    (is (= expected
           el-result))
    (is (= expected
           mock-result))))

(deftest test-elastic-mock-parity-sort-desc
  (let [data [{:k1 "121" :k2 {:k21 "a" :k22 "1"}}
              {:k1 "758" :k2 {:k21 "c" :k22 "2"}}
              {:k1 "752" :k2 {:k21 "d" :k22 "2"}}
              {:k1 "751" :k2 {:k21 "c" :k22 "2"}}
              {:k1 "121" :k2 {:k21 "e" :k22 "3"}}]
        query {:select [:k1 :k2.k21]
               :size   50
               :filter [:eq "k2.k22" "2"]
               :sort   [:k1 "desc"]}
        expected {:total 3
                  :from  0
                  :size  50
                  :hits  [{:k1 "758" :k2 {:k21 "c"}}
                          {:k1 "752" :k2 {:k21 "d"}}
                          {:k1 "751" :k2 {:k21 "c"}}]}
        [el-result mock-result] (test-query data query)]
    (is (= expected
           el-result))
    (is (= expected
           mock-result))))

(deftest test-elastic-mock-parity-sort-desc-1
  (let [data [{:k1 "121" :k2 {:k21 "a" :k22 "1"}}
              {:k1 "758" :k2 {:k21 "1" :k22 "2"}}
              {:k1 "758" :k2 {:k21 "2" :k22 "2"}}
              {:k1 "250" :k2 {:k21 "3" :k22 "2"}}
              {:k1 "121" :k2 {:k21 "4" :k22 "2"}}]
        query {:select [:k1 :k2.k21]
               :size   50
               :filter [:eq "k2.k22" "2"]
               :sort   [:k1 "desc"
                        :k2.k21 "asc"]}
        expected {:total 4
                  :from  0
                  :size  50
                  :hits  [{:k1 "758" :k2 {:k21 "1"}}
                          {:k1 "758" :k2 {:k21 "2"}}
                          {:k1 "250" :k2 {:k21 "3"}}
                          {:k1 "121" :k2 {:k21 "4"}}]}
        [el-result mock-result] (test-query data query)]
    (is (= expected
           el-result))
    (is (= expected
           mock-result))))

(deftest test-elastic-mock-parity-sort-desc-2
  (let [data [{:k1 "121" :k2 {:k21 "a" :k22 "1"}}
              {:k1 "758" :k2 {:k21 "1" :k22 "2"}}
              {:k1 "758" :k2 {:k21 "2" :k22 "2"}}
              {:k1 "250" :k2 {:k21 "3" :k22 "2"}}
              {:k1 "121" :k2 {:k21 "4" :k22 "2"}}]
        query {:select [:k1 :k2.k21]
               :size   50
               :filter [:eq "k2.k22" "2"]
               :sort   [:k1 "desc"
                        :k2.k21 "desc"]}
        expected {:total 4
                  :from  0
                  :size  50
                  :hits  [{:k1 "758" :k2 {:k21 "2"}}
                          {:k1 "758" :k2 {:k21 "1"}}
                          {:k1 "250" :k2 {:k21 "3"}}
                          {:k1 "121" :k2 {:k21 "4"}}]}
        [el-result mock-result] (test-query data query)]
    (is (= expected
           el-result))
    (is (= expected
           mock-result))))

(deftest test-elastic-mock-parity-sort-asc-1
  (let [data [{:k1 "121" :k2 {:k21 "a" :k22 "1"}}
              {:k1 "758" :k2 {:k21 "1" :k22 "2"}}
              {:k1 "758" :k2 {:k21 "2" :k22 "2"}}
              {:k1 "250" :k2 {:k21 "3" :k22 "2"}}
              {:k1 "121" :k2 {:k21 "4" :k22 "2"}}]
        query {:select [:k1 :k2.k21]
               :size   50
               :filter [:eq "k2.k22" "2"]
               :sort   [:k1 "asc"
                        :k2.k21 "desc"]}
        expected {:total 4
                  :from  0
                  :size  50
                  :hits  [{:k1 "121" :k2 {:k21 "4"}}
                          {:k1 "250" :k2 {:k21 "3"}}
                          {:k1 "758" :k2 {:k21 "2"}}
                          {:k1 "758" :k2 {:k21 "1"}}]}
        [el-result mock-result] (test-query data query)]
    (is (= expected
           el-result))
    (is (= expected
           mock-result))))

(deftest test-elastic-mock-parity-sort-asc-2
  (let [data [{:k1 1}
              {:k1 20}
              {:k1 3}
              {:k1 400}
              {:k1 5}]
        query {:size 50
               :sort [:k1 "asc-number"]}
        expected {:total 5
                  :from  0
                  :size  50
                  :hits  [{:k1 1}
                          {:k1 3}
                          {:k1 5}
                          {:k1 20}
                          {:k1 400}]}
        [el-result mock-result] (test-query data query)]
    (is (= expected
           el-result))
    (is (= expected
           mock-result))))

(deftest test-elastic-mock-parity-sort-desc-2-2
  (let [data [{:k1 1}
              {:k1 20}
              {:k1 3}
              {:k1 400}
              {:k1 5}]
        query {:size 50
               :sort [:k1 :desc-number]}
        expected {:total 5
                  :from  0
                  :size  50
                  :hits  [{:k1 400}
                          {:k1 20}
                          {:k1 5}
                          {:k1 3}
                          {:k1 1}]}
        [el-result mock-result] (test-query data query)]
    (is (= expected
           el-result))
    (is (= expected
           mock-result))))

(deftest test-elastic-mock-parity-test-exists-field-1
  (let [data [{:attrs {:top-parent-id #uuid "1111a4b3-1c40-48ec-b6c9-b9cceeea6bb8"
                       :top-gcc-id    #uuid "2222a4b3-1c40-48ec-b6c9-b9cceeea6bb8"}}
              {:attrs {:top-parent-id #uuid "3333a4b3-1c40-48ec-b6c9-b9cceeea6bb8"
                       :top-gcc-id    #uuid "4444a4b3-1c40-48ec-b6c9-b9cceeea6bb8"}}
              {:attrs {:top-parent-id #uuid "5555a4b3-1c40-48ec-b6c9-b9cceeea6bb8"
                       :top-gcc-id    #uuid "2222a4b3-1c40-48ec-b6c9-b9cceeea6bb8"}}
              {:attrs {:top-parent-id #uuid "2222a4b3-1c40-48ec-b6c9-b9cceeea6bb8"
                       :top-gcc-id    #uuid "8888a4b3-1c40-48ec-b6c9-b9cceeea6bb8"}}]
        query {:filter [:exists :attrs.top-parent-id]}
        expected {:total 4
                  :from  0
                  :size  50
                  :hits  data}
        [el-result mock-result] (test-query data query)]
    (is (= expected
           el-result))
    (is (= expected
           mock-result))))

(deftest test-elastic-mock-parity-test-exists-field-2
  (let [data [{:attrs {:top-parent-id #uuid "1111a4b3-1c40-48ec-b6c9-b9cceeea6bb8"
                       :top-gcc-id    #uuid "2222a4b3-1c40-48ec-b6c9-b9cceeea6bb8"}}
              {:attrs {:top-parent-id #uuid "3333a4b3-1c40-48ec-b6c9-b9cceeea6bb8"
                       :top-gcc-id    #uuid "4444a4b3-1c40-48ec-b6c9-b9cceeea6bb8"}}
              {:attrs {:top-parent-id #uuid "5555a4b3-1c40-48ec-b6c9-b9cceeea6bb8"
                       :top-gcc-id    #uuid "2222a4b3-1c40-48ec-b6c9-b9cceeea6bb8"}}
              {:attrs {:top-parent-id #uuid "2222a4b3-1c40-48ec-b6c9-b9cceeea6bb8"
                       :top-gcc-id    #uuid "8888a4b3-1c40-48ec-b6c9-b9cceeea6bb8"}}]
        query {:filter [:not [:exists :attrs.top-parent-id]]}
        expected {:total 0
                  :from  0
                  :size  50
                  :hits  []}
        [el-result mock-result] (test-query data query)]
    (is (= expected
           el-result))
    (is (= expected
           mock-result))))

(deftest test-elastic-mock-parity-test-order-of-exact-match
  (let [data [{:k1 "120" :attrs {:type :booking-company}}
              {:k1 "10" :k2 "1adc" :attrs {:type :breaking-company}}
              {:k1 "100" :k2 "10" :attrs {:type :breaking-company}}
              {:k1 "1011" :k2 "1011" :attrs {:type :breaking-company}}
              {:k1 "lem" :k2 "4adcor" :attrs {:type :breaking-company}}
              {:k1 "ipsum" :k2 "5abc" :attrs {:type :breaking-company}}
              {:k1 "dol" :k2 "6abc" :attrs {:type :breaking-company}}
              {:k1 "10" :k2 "zeko" :attrs {:type :breaking-company}}
              {:k1 "amet" :k2 "7abc" :attrs {:type :booking-company}}]
        query {:search [:fields [:k1 :k2]
                        :value "10"]}
        expected {:from  0
                  :hits  [{:attrs {:type :breaking-company}
                           :k1    "100"
                           :k2    "10"}
                          {:attrs {:type :breaking-company}
                           :k1    "10"
                           :k2    "1adc"}
                          {:attrs {:type :breaking-company}
                           :k1    "10"
                           :k2    "zeko"}
                          {:attrs {:type :breaking-company}
                           :k1    "1011"
                           :k2    "1011"}]
                  :size  50
                  :total 4}

        [el-result _mock-result] (test-query data query)]
    (is (= expected
           el-result))))




