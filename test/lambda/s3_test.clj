(ns lambda.s3-test
  (:require
   [aws.runtime :as runtime]
   [sdk.aws.s3 :as s3]
   [sdk.aws.common :as common]
   [edd.core :as edd]
   [lambda.test.fixture.client :as client]
   [lambda.filters :as fl]
   [clojure.string :as string]
   [lambda.test.fixture.core :refer [mock-core]]
   [clojure.test :refer [is  deftest testing]]
   [clojure.tools.logging :as log])
  (:import (clojure.lang ExceptionInfo)))

(def interaction-id #uuid "0000b7b5-9f50-4dc4-86d1-2e4fe1f6d491")
(def request-id #uuid "1111b7b5-9f50-4dc4-86d1-2e4fe1f6d491")

(defn records
  [key]
  {:Records
   [{:eventName         "ObjectCreated:Put",
     :awsRegion         "eu-central-1",
     :responseElements
     {:x-amz-request-id "EXAMPLE123456789",
      :x-amz-id-2
      "EXAMPLE123/5678abcdefghijklambdaisawesome/mnopqrstuvwxyzABCDEFGH"},
     :requestParameters {:sourceIPAddress "127.0.0.1"},
     :userIdentity      {:principalId "EXAMPLE"},
     :eventVersion      "2.0",
     :eventTime         "1970-01-01T00:00:00.000Z",
     :eventSource       "aws:s3",
     :s3
     {:s3SchemaVersion "1.0",
      :configurationId "testConfigRule",
      :bucket
      {:name          "example-bucket",
       :ownerIdentity {:principalId "EXAMPLE"},
       :arn           "arn:aws:s3:::example-bucket"},
      :object
      {:key       key
       :size      1024,
       :eTag      "0123456789abcdef0123456789abcdef",
       :sequencer "0A1B2C3D4E5F678901"}}}]})

(deftest test-s3-bucket-request
  (testing
   "Autorization header request"
    (let [s3-key (str "test/2021-12-27/"
                      interaction-id
                      "/"
                      request-id
                      ".csv")]
      (mock-core
       {:env {"Region" "eu-west-1"}
        :responses [{:url  (str "https://s3.eu-west-1.amazonaws.com/example-bucket/"
                                s3-key)
                     :method :get
                     :response {:body (char-array "Of something")}}]}
       (runtime/lambda-requests
        {:edd {:config {:secrets-file "files/secret-eu-west.json"}}}
        (fn [ctx body]
               ;; "Slurp content of S3 request into response"
          (log/info (:commands body))
          (let [commands (:commands body)
                cmd (first commands)
                response (assoc cmd :body
                                (slurp (:body cmd)))]
            (assoc body :commands [response]
                   :user (get-in ctx [:user :id])
                   :role (get-in ctx [:user :role]))))
        [(records s3-key)]
        :filters [fl/from-bucket])
       (is (= [{:body   {:commands       [{:body   "Of something"
                                           :cmd-id :object-uploaded
                                           :bucket "example-bucket"
                                           :date   "2021-12-27"
                                           :id     request-id
                                           :key    s3-key}]
                         :user           "local-svc"
                         :meta           {:realm :test
                                          :user  {:email "non-interractiva@s3.amazonws.com"
                                                  :id    #uuid "1111b7b5-9f50-4dc4-86d1-2e4fe1f6d491"
                                                  :role  :non-interactive}}
                         :role           :non-interactive
                         :interaction-id interaction-id
                         :request-id     request-id}
                :method :post
                :url    "http://mock/2018-06-01/runtime/invocation/00000000-0000-0000-0000-000000000000/response"}
               {:as              :stream
                :headers         {"Authorization"        "AWS4-HMAC-SHA256 Credential=/20200426/eu-west-1/s3/aws4_request, SignedHeaders=host;x-amz-content-sha256;x-amz-date;x-amz-security-token, Signature=568189122a5f82412b53360d9a4fd827043d0f8c86029e0c561e913e41359a57"
                                  "x-amz-content-sha256" "UNSIGNED-PAYLOAD"
                                  "x-amz-date"           "20200426T061823Z"
                                  "x-amz-security-token" nil}
                :method          :get
                :connect-timeout 300
                :idle-timeout    5000
                :url             (str "https://s3.eu-west-1.amazonaws.com/example-bucket/" s3-key)}]
              (client/traffic-edn)))))))

(deftest test-s3-bucket-request-when-folder-craeted
  (let [s3-key (str "test/2021-12-27/"
                    interaction-id
                    "/")]
    (mock-core
     {:responses [{:method :get
                   :url (str "https://s3.eu-central-1.amazonaws.com/example-bucket/"
                             s3-key)
                   :response {:body (char-array "Of something")}}]}
     (runtime/lambda-requests
      {:edd {:config {:secrets-file "files/secret-eu-west.json"}}}
      edd/handler
      [(records s3-key)]
      :filters [fl/from-bucket])

     (is (= [{:body   "{}"
              :method :post
              :url    "http://mock/2018-06-01/runtime/invocation/00000000-0000-0000-0000-000000000000/response"}]
            (client/traffic))))))

(deftest s3-cond
  (let [resp (apply (:condition fl/from-bucket)
                    [{:body (records "test/key")}])]
    (is (= resp true))))

(deftest test-filter-key
  (is (= {:interaction-id #uuid "af42568c-f8e9-40ff-9329-d13f1c82fce5"
          :realm          "test"
          :date           "2020-12-27"
          :id             #uuid "af42568c-f8e9-40ff-9329-d13f1c82fca3"
          :request-id     #uuid "af42568c-f8e9-40ff-9329-d13f1c82fca3"}
         (fl/parse-key "test/2020-12-27/af42568c-f8e9-40ff-9329-d13f1c82fce5/af42568c-f8e9-40ff-9329-d13f1c82fca3.matching1L.csv")))
  (is (= {:interaction-id #uuid "af42568c-f8e9-40ff-9329-d13f1c82fce5"
          :realm          "prod"
          :date           "2020-12-27"
          :id             #uuid "af42568c-f8e9-40ff-9329-d13f1c82fca3"
          :request-id     #uuid "af42568c-f8e9-40ff-9329-d13f1c82fca3"}
         (fl/parse-key "upload/2020-12-27/af42568c-f8e9-40ff-9329-d13f1c82fce5/af42568c-f8e9-40ff-9329-d13f1c82fca3.matching1L.csv")))
  (is (= {:interaction-id #uuid "af42568c-f8e9-40ff-9329-d13f1c82fce5"
          :realm          "prod"
          :id             #uuid "af42568c-f8e9-40ff-9329-d13f1c82fca3"
          :date           "2021-08-21"
          :request-id     #uuid "af42568c-f8e9-40ff-9329-d13f1c82fca3"}
         (fl/parse-key "prod/2021-08-21/af42568c-f8e9-40ff-9329-d13f1c82fce5/af42568c-f8e9-40ff-9329-d13f1c82fca3.matching1L.csv")))
  (let [interaction-id #uuid "86203547-20be-4683-8cbd-b65c710f357a"
        request-id #uuid "59dea305-5120-4980-ab5c-eeddc0774f6e"
        id #uuid "6d2423a7-2dae-4a3f-982f-18104d58a8fc"]
    (is (= {:interaction-id interaction-id
            :realm          "prod"
            :id             id
            :date           "2021-08-21"
            :request-id     request-id}
           (fl/parse-key (str "prod/2021-08-21/"
                              interaction-id
                              "/"
                              id
                              "/"
                              request-id
                              ".matching1L.csv")))))
  (is (thrown?
       ExceptionInfo
       (fl/parse-key "test/af42568c-f8e9-40ff-9329-d13f1c82fce5/af42568c-f8e9-40ff-9329-d13f1c82fcz3.matching1L.csv"))))

(defn parse-url
  [url]
  (let [[url query] (string/split url #"\?")
        query (string/split query #"&")
        query (reduce
               (fn [p v]
                 (let [[key value] (string/split v #"=")]
                   (assoc p key value)))
               {}
               query)]
    {:url url
     :query query}))

(defn creadentials
  []
  {:aws-access-key-id "ASIAWP43YVBNOSIGRP5Y"
   :aws-secret-access-key "VW90WSgYSvtJJhiPN3wZ+RWZflGhkXvqe4kek1/a"
   :aws-session-token "IQoJb3JpZ2luX2VjEKP//////////wEaCWV1LXdlc3QtMSJHMEUCIQCFFC0xncoL0saR9DMoL5oXtbcCJ0y4Az/5HTqNlnPA1gIgYUjghlsFTfL1UIM8CHQ5V2F4D3m8DPaSZEiCg9dxpoQqjAQIXBACGgw0NDY0NjY0MDIzOTQiDABhvnwNj7KVqCErLirpA+IzuE2uipnjC/qMx1IspJq5qxHOroWuQGtJ0kbDEnUVbrmSzb+zNUs4R5C32WuFKuft0gXYF62rZcoEG18cfkyNHwb7NCQK0YHXRiiNxS7wOSlKI6Z093JGVwn52azyicSV07Ba6v3aG+ksGL55UOCL93GRS28eQaOLdTeBDsG12EIMRdegkrALdkapiBG7ywvcwNeObxE7jQZYfh4k1L1uZqn/KtfkJU4Oy+1yUdpk7Vyc04DPpSq6Sdd6YQLAPco3ei8tFdxtGAj35CTPKwfwEGhn2p+BhXchASmh3Sc02GIjdfK8oUjeVPgBw4s1r/2A0OVCnrSFuMh5rTr8LdEWvi1kuLdI9Q/PIIzzlL0fIWd406k7Gla8kNPF/TBuTYCkh2CEwKv6Lq9i+7M+jAP1cEhgdsWkYH+uUn5bnV46E+bXDtmA6TmBq9P0phTgmfVkDV22cI9aL+pREDNonYqD1OJ2NDguqrQ67FKCIosh19piI5tj5ljbD1qcVoiC6vDX0dyZ7u0Xw4/kHSF4wOrYiY+Zui7dr0DNg8KkRULfL0jTNk7ACI0rQsNL1l3mxsh8ji9buNsg/tJsIl1BfAc1aeDKLK8PE7naEMvPuJ8r/BHXxS56/JUqkH2LevTzSiMogs923NL6VTDytb6tBjqUAghwJzWTafQh8f8YIeuXp9S3X830SkgSChKhTU2oxnYJpstqXSl8MGGq1+BE3fPiPLxo+9THFHXLGOTVNeaxWl0Sr72b6O/jVv2Q9UBY3MSiT2UYE5GBDFnNTlYbH4jNcn1gEvOszEQCRUR5mq9blUoPQCC2Qb/imSBO9ubAT2iL9sqleOAh6aEp5WClC/9xVgr646O/6iJRe5crPsVf1VR63cz1l3IVCO/WIcBGF3HonhqqySM8PPbKzejp+vTpkQTZDjky+w7Pt6nS/Psn95bwXYZK5mYVTsZFhZ8SyfdtQAd0VXvVNqMNDM+02m6HDXqaGbOagFYtuNVjG9HoY3RrHfJ3PKipvhc1HcW1IZkJM8BSIA=="})

(deftest s3-presigned-url-test
  (with-redefs [common/create-date (fn []
                                     "20240123T105604Z")]
    (let [expected  (parse-url "https://123-upload-test.s3.eu-west-1.amazonaws.com/file1.gz?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=ASIAWP43YVBNOSIGRP5Y%2F20240123%2Feu-west-1%2Fs3%2Faws4_request&X-Amz-Date=20240123T105604Z&X-Amz-Expires=604800&X-Amz-SignedHeaders=host&X-Amz-Security-Token=IQoJb3JpZ2luX2VjEKP%2F%2F%2F%2F%2F%2F%2F%2F%2F%2FwEaCWV1LXdlc3QtMSJHMEUCIQCFFC0xncoL0saR9DMoL5oXtbcCJ0y4Az%2F5HTqNlnPA1gIgYUjghlsFTfL1UIM8CHQ5V2F4D3m8DPaSZEiCg9dxpoQqjAQIXBACGgw0NDY0NjY0MDIzOTQiDABhvnwNj7KVqCErLirpA%2BIzuE2uipnjC%2FqMx1IspJq5qxHOroWuQGtJ0kbDEnUVbrmSzb%2BzNUs4R5C32WuFKuft0gXYF62rZcoEG18cfkyNHwb7NCQK0YHXRiiNxS7wOSlKI6Z093JGVwn52azyicSV07Ba6v3aG%2BksGL55UOCL93GRS28eQaOLdTeBDsG12EIMRdegkrALdkapiBG7ywvcwNeObxE7jQZYfh4k1L1uZqn%2FKtfkJU4Oy%2B1yUdpk7Vyc04DPpSq6Sdd6YQLAPco3ei8tFdxtGAj35CTPKwfwEGhn2p%2BBhXchASmh3Sc02GIjdfK8oUjeVPgBw4s1r%2F2A0OVCnrSFuMh5rTr8LdEWvi1kuLdI9Q%2FPIIzzlL0fIWd406k7Gla8kNPF%2FTBuTYCkh2CEwKv6Lq9i%2B7M%2BjAP1cEhgdsWkYH%2BuUn5bnV46E%2BbXDtmA6TmBq9P0phTgmfVkDV22cI9aL%2BpREDNonYqD1OJ2NDguqrQ67FKCIosh19piI5tj5ljbD1qcVoiC6vDX0dyZ7u0Xw4%2FkHSF4wOrYiY%2BZui7dr0DNg8KkRULfL0jTNk7ACI0rQsNL1l3mxsh8ji9buNsg%2FtJsIl1BfAc1aeDKLK8PE7naEMvPuJ8r%2FBHXxS56%2FJUqkH2LevTzSiMogs923NL6VTDytb6tBjqUAghwJzWTafQh8f8YIeuXp9S3X830SkgSChKhTU2oxnYJpstqXSl8MGGq1%2BBE3fPiPLxo%2B9THFHXLGOTVNeaxWl0Sr72b6O%2FjVv2Q9UBY3MSiT2UYE5GBDFnNTlYbH4jNcn1gEvOszEQCRUR5mq9blUoPQCC2Qb%2FimSBO9ubAT2iL9sqleOAh6aEp5WClC%2F9xVgr646O%2F6iJRe5crPsVf1VR63cz1l3IVCO%2FWIcBGF3HonhqqySM8PPbKzejp%2BvTpkQTZDjky%2Bw7Pt6nS%2FPsn95bwXYZK5mYVTsZFhZ8SyfdtQAd0VXvVNqMNDM%2B02m6HDXqaGbOagFYtuNVjG9HoY3RrHfJ3PKipvhc1HcW1IZkJM8BSIA%3D%3D&X-Amz-Signature=2568e135d6b7a1c73465bc97d9376184c927d2a5e0dcbff08402ad30d43b1a30")
          expires 604800
          response (s3/presigned-url
                    {:debug true
                     :aws (assoc (creadentials)
                                 :region "eu-west-1")}
                    {:expires expires
                     :object {:s3
                              {:bucket
                               {:name "123-upload-test"}
                               :object
                               {:key "file1.gz"}}}})]
      (is (= expected
             (parse-url response))))))
