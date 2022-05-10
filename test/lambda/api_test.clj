(ns lambda.api-test
  (:require [clojure.test :refer [deftest is testing]]
            [aws.runtime :as runtime]
            [lambda.filters :as fl]
            [lambda.test.fixture.client :as client]
            [lambda.test.fixture.core :refer [mock-core]]
            [lambda.util :as util]))

(def cmd-id #uuid "c5c4d4df-0570-43c9-a0c5-2df32f3be124")

(def dummy-cmd
  {:commands [{:id     cmd-id
               :cmd-id :dummy-cmd}]
   :user     {:selected-role :group-2}})

(def token-with-groups "eyJraWQiOiJYNXFKM3Z5ZEJHeCtoT1Jvb1hDOVlrbWpxQzU4aUU3SzVKVnBQWWcrOWpvPSIsImFsZyI6IlJTMjU2In0.eyJzdWIiOiI4OTljMGM1Ny02NDZlLTQyOWQtYTVhNi1iZjhkZWM3YzRhODYiLCJhdWQiOiIxZW4xdmJjNnMxazBjcHZoaDBydGc1ZzFkOCIsImNvZ25pdG86Z3JvdXBzIjpbInJvbGVzLWdyb3VwLTIiLCJyb2xlcy1ncm91cC0zIiwicmVhbG0tcHJvZCIsInJvbGVzLWdyb3VwLTEiXSwiZW1haWxfdmVyaWZpZWQiOnRydWUsImV2ZW50X2lkIjoiZTNlYjIxMjEtOGIwYy00MWQ4LWI3ZWYtZjAyMTJjOWRkNDI1IiwidG9rZW5fdXNlIjoiaWQiLCJhdXRoX3RpbWUiOjE2NTA5ODkxNTUsImlzcyI6Imh0dHBzOlwvXC9jb2duaXRvLWlkcC5ldS13ZXN0LTEuYW1hem9uYXdzLmNvbVwvZXUtd2VzdC0xX3h3QVpFbGc2UCIsImNvZ25pdG86dXNlcm5hbWUiOiJqb2huLnNtaXRoQGV4YW1wbGUuY29tIiwiZXhwIjoxNjUwOTkyNzU1LCJpYXQiOjE2NTA5ODkxNTUsImVtYWlsIjoiam9obi5zbWl0aEBleGFtcGxlLmNvbSJ9.C8rek8gX1PvYR-wnSDCGx6s15ucS5mD-7J1mQMcNS5ZhMaBDwMFqHVNORGhlgrPolnAl1u76ytSoORmtgAfBR9mf9NwqpTVj3eAMskl_aoFR603WNa2w8SFtcPVLexgOp_kQADVBrGhdPOoftASNsCobf6EpdlyiidUsu7BMal9WhyRI1yPt_Ou4WGxusy-Ojuif_Ef6C_fGv3g6ySDjTV7A_cTA-VMietwIQ6e2N2I6l9uhg4lQxWMrZlN19YTLJF6aI6BRGzjur-CLN0SosmMB7DEZAUD6lQVUwdVLRnUeOp2xVJWW7crLan5VoB9TzHMEjhppiTRwEVnfvRguyA")

(defn authorization-header-request
  [body & {:keys [token path httpMethod isBase64Encoded]
           :or {token token-with-groups
                path "/path/to/resource"
                isBase64Encoded false
                httpMethod "POST"}}]
  {:path                  path,
   :queryStringParameters {:foo "bar"},
   :pathParameters        {:proxy "/path/to/resource"},
   :headers
   {:Upgrade-Insecure-Requests    "1",
    :X-Amz-Cf-Id
    "cDehVQoZnx43VYQb9j2-nvCh-9z396Uhbp027Y2JvkCPNLmGJHqlaA==",
    :CloudFront-Is-Tablet-Viewer  "false",
    :CloudFront-Forwarded-Proto   "https",
    :X-Forwarded-Proto            "https",
    :X-Forwarded-Port             "443",
    :x-authorization              token
    :Accept
    "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
    :Accept-Encoding              "gzip, deflate, sdch",
    :X-Forwarded-For              "127.0.0.1, 127.0.0.2",
    :CloudFront-Viewer-Country    "US",
    :Accept-Language              "en-US,en;q=0.8",
    :Cache-Control                "max-age=0",
    :CloudFront-Is-Desktop-Viewer "true",
    :Via
    "1.1 08f323deadbeefa7af34d5feb414ce27.cloudfront.net (CloudFront)",
    :CloudFront-Is-SmartTV-Viewer "false",
    :CloudFront-Is-Mobile-Viewer  "false",
    :Host
    "1234567890.execute-api.eu-west-1.amazonaws.com",
    :User-Agent                   "Custom User Agent String"},
   :stageVariables        {:baz "qux"},
   :resource              "/{proxy+}",
   :isBase64Encoded      isBase64Encoded ,
   :multiValueQueryStringParameters
   {:foo ["bar"]},
   :httpMethod            httpMethod,
   :requestContext
   {:path             "/prod/path/to/resource",
    :identity
    {:caller                        nil,
     :sourceIp                      "127.0.0.1",
     :cognitoIdentityId             nil,
     :userAgent                     "Custom User Agent String",
     :cognitoAuthenticationProvider nil,
     :accessKey                     nil,
     :accountId                     nil,
     :user                          nil,
     :cognitoAuthenticationType     nil,
     :cognitoIdentityPoolId         nil,
     :userArn                       nil},
    :stage            "prod",
    :protocol         "HTTP/1.1",
    :resourcePath     "/{proxy+}",
    :resourceId       "123456",
    :requestTime      "09/Apr/2015:12:34:56 +0000",
    :requestId
    "c6af9ac6-7b61-11e6-9a41-93e8deadbeef",
    :httpMethod       "POST",
    :requestTimeEpoch 1428582896000,
    :accountId        "123456789012",
    :apiId            "1234567890"},
   :body                  body,
   :multiValueHeaders
   {:Upgrade-Insecure-Requests    ["1"],
    :X-Amz-Cf-Id
    ["cDehVQoZnx43VYQb9j2-nvCh-9z396Uhbp027Y2JvkCPNLmGJHqlaA=="],
    :CloudFront-Is-Tablet-Viewer  ["false"],
    :CloudFront-Forwarded-Proto   ["https"],
    :X-Forwarded-Proto            ["https"],
    :X-Forwarded-Port             ["443"],
    :Accept
    ["text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8"],
    :Accept-Encoding              ["gzip, deflate, sdch"],
    :X-Forwarded-For              ["127.0.0.1, 127.0.0.2"],
    :CloudFront-Viewer-Country    ["US"],
    :Accept-Language              ["en-US,en;q=0.8"],
    :Cache-Control                ["max-age=0"],
    :CloudFront-Is-Desktop-Viewer ["true"],
    :Via
    ["1.1 08f323deadbeefa7af34d5feb414ce27.cloudfront.net (CloudFront)"],
    :CloudFront-Is-SmartTV-Viewer ["false"],
    :CloudFront-Is-Mobile-Viewer  ["false"],
    :Host
    ["0123456789.execute-api.eu-west-1.amazonaws.com"],
    :User-Agent                   ["Custom User Agent String"]}})

(defn custom-authorizer-request
  [body & {:keys [httpMethod
                  isBase64Encoded]
           :or {isBase64Encoded false
                httpMethod "POST"}}]
  {:path                            "/integration/canary/event-log",
   :queryStringParameters           "None",
   :pathParameters                  {:stage "canary", :function "event-log"},
   :headers
   {:x-amzn-tls-version     "TLSv1.2",
    :X-Forwarded-Proto      "https",
    :X-Forwarded-Port       "443",
    :Accept                 "*/*",
    :x-amzn-cipher-suite    "ECDHE-RSA-AES128-GCM-SHA256",
    :Accept-Encoding        "gzip, deflate, br",
    :X-Forwarded-For        "10.223.44.173, 10.223.128.204, 10.223.128.218",
    :x-amzn-vpc-id          "vpc-08466e8fa1fc4d330",
    :x-amzn-vpce-id         "vpce-0a42e0e4f6052d2a6",
    :x-amzn-vpce-config     "0",
    :Host                   "api.lime-dev12.internal.rbigroup.cloud",
    :Content-Type           "application/json",
    :Postman-Token          "fbfe1147-6459-454d-a6d7-68629a2022e2",
    :x-amzn-vpce-policy-url "MQ==;vpce-svc-01fedad8f6ddc0953",
    :User-Agent             "PostmanRuntime/7.29.0",
    :X-Amzn-Trace-Id
    "Self=1-624c4641-39061598649a512f2b364894;Root=1-624c4641-31a8bde2177ec9aa0409676b"},
   :stageVariables                  {:EnvironmentNameUpper "DEV12"},
   :resource                        "/integration/{stage}/{function}",
   :isBase64Encoded                 isBase64Encoded,
   :multiValueQueryStringParameters "None",
   :httpMethod                      httpMethod,
   :requestContext
   {:path              "/integration/canary/event-log",
    :identity
    {:caller                        "None",
     :vpceId                        "vpce-0a42e0e4f6052d2a6",
     :sourceIp                      "10.223.128.218",
     :principalOrgId                "None",
     :cognitoIdentityId             "None",
     :vpcId                         "vpc-08466e8fa1fc4d330",
     :userAgent                     "PostmanRuntime/7.29.0",
     :cognitoAuthenticationProvider "None",
     :accessKey                     "None",
     :accountId                     "None",
     :user                          "None",
     :cognitoAuthenticationType     "None",
     :cognitoIdentityPoolId         "None",
     :userArn                       "None"},
    :stage             "DEV12",
    :protocol          "HTTP/1.1",
    :resourcePath      "/integration/{stage}/{function}",
    :domainPrefix      "api",
    :resourceId        "2a2tlx",
    :requestTime       "05/Apr/2022:13:38:09 +0000",
    :requestId         "7e42b0a7-465e-4f43-aaf0-95e049fcf05b",
    :domainName        "api.lime-dev12.internal.rbigroup.cloud",
    :authorizer
    {:cognito:groups     "realm-test,roles-admins",
     :user               "rbi-glms-m2m-prod"
     :token_use          "m2m"
     :email              "rbi-glms-m2m-prod@rbi.cloud"
     :integrationLatency 207},
    :httpMethod        "POST",
    :requestTimeEpoch  1649165889404,
    :accountId         "421990764474",
    :extendedRequestId "QG_qQHmhFiAFsLQ=",
    :apiId             "t4rh2tqly8"},
   :body                            body,
   :multiValueHeaders
   {:x-amzn-tls-version     ["TLSv1.2"],
    :X-Forwarded-Proto      ["https"],
    :X-Forwarded-Port       ["443"],
    :Accept                 ["*/*"],
    :x-amzn-cipher-suite    ["ECDHE-RSA-AES128-GCM-SHA256"],
    :Accept-Encoding        ["gzip, deflate, br"],
    :X-Forwarded-For        ["10.223.44.173, 10.223.128.204, 10.223.128.218"],
    :x-amzn-vpc-id          ["vpc-08466e8fa1fc4d330"],
    :x-amzn-vpce-id         ["vpce-0a42e0e4f6052d2a6"],
    :x-amzn-vpce-config     ["0"],
    :Host                   ["api.lime-dev12.internal.rbigroup.cloud"],
    :Content-Type           ["application/json"],
    :Postman-Token          ["fbfe1147-6459-454d-a6d7-68629a2022e2"],
    :x-amzn-vpce-policy-url ["MQ==;vpce-svc-01fedad8f6ddc0953"],
    :User-Agent             ["PostmanRuntime/7.29.0"],
    :X-Amzn-Trace-Id
    ["Self=1-624c4641-39061598649a512f2b364894;Root=1-624c4641-31a8bde2177ec9aa0409676b"]}})

(defn cognito-authorizer-request
  [body & {:keys [path httpMethod isBase64Encoded]
           :or {isBase64Encoded false
                httpMethod "POST"
                path "/integration/canary/event-log"}}]
  {:path                            path,
   :queryStringParameters           "None",
   :pathParameters                  {:stage "canary", :function "event-log"},
   :headers
   {:x-amzn-tls-version     "TLSv1.2",
    :X-Forwarded-Proto      "https",
    :X-Forwarded-Port       "443",
    :Accept                 "*/*",
    :x-amzn-cipher-suite    "ECDHE-RSA-AES128-GCM-SHA256",
    :Accept-Encoding        "gzip, deflate, br",
    :X-Forwarded-For        "10.223.44.173, 10.223.128.204, 10.223.128.218",
    :x-amzn-vpc-id          "vpc-08466e8fa1fc4d330",
    :x-amzn-vpce-id         "vpce-0a42e0e4f6052d2a6",
    :x-amzn-vpce-config     "0",
    :Host                   "api.lime-dev12.internal.rbigroup.cloud",
    :Content-Type           "application/json",
    :Postman-Token          "fbfe1147-6459-454d-a6d7-68629a2022e2",
    :x-amzn-vpce-policy-url "MQ==;vpce-svc-01fedad8f6ddc0953",
    :User-Agent             "PostmanRuntime/7.29.0",
    :X-Amzn-Trace-Id
    "Self=1-624c4641-39061598649a512f2b364894;Root=1-624c4641-31a8bde2177ec9aa0409676b"},
   :stageVariables                  {:EnvironmentNameUpper "DEV12"},
   :resource                        "/integration/{stage}/{function}",
   :isBase64Encoded                 isBase64Encoded,
   :multiValueQueryStringParameters "None",
   :httpMethod                      httpMethod,
   :requestContext
   {:path              "/integration/canary/event-log",
    :identity
    {:caller                        "None",
     :vpceId                        "vpce-0a42e0e4f6052d2a6",
     :sourceIp                      "10.223.128.218",
     :principalOrgId                "None",
     :cognitoIdentityId             "None",
     :vpcId                         "vpc-08466e8fa1fc4d330",
     :userAgent                     "PostmanRuntime/7.29.0",
     :cognitoAuthenticationProvider "None",
     :accessKey                     "None",
     :accountId                     "None",
     :user                          "None",
     :cognitoAuthenticationType     "None",
     :cognitoIdentityPoolId         "None",
     :userArn                       "None"},
    :stage             "DEV12",
    :protocol          "HTTP/1.1",
    :resourcePath      "/integration/{stage}/{function}",
    :domainPrefix      "api",
    :resourceId        "2a2tlx",
    :requestTime       "05/Apr/2022:13:38:09 +0000",
    :requestId         "7e42b0a7-465e-4f43-aaf0-95e049fcf05b",
    :domainName        "api.lime-dev12.internal.rbigroup.cloud",
    :authorizer        {:claims {:cognito:groups     "realm-test,roles-users",
                                 :user               "rbi-glms-m2m-prod"
                                 :token_use          "id"
                                 :email              "rbi-glms-m2m-prod@rbi.cloud"
                                 :integrationLatency 207}},
    :httpMethod        "POST",
    :requestTimeEpoch  1649165889404,
    :accountId         "421990764474",
    :extendedRequestId "QG_qQHmhFiAFsLQ=",
    :apiId             "t4rh2tqly8"},
   :body                            body,
   :multiValueHeaders
   {:x-amzn-tls-version     ["TLSv1.2"],
    :X-Forwarded-Proto      ["https"],
    :X-Forwarded-Port       ["443"],
    :Accept                 ["*/*"],
    :x-amzn-cipher-suite    ["ECDHE-RSA-AES128-GCM-SHA256"],
    :Accept-Encoding        ["gzip, deflate, br"],
    :X-Forwarded-For        ["10.223.44.173, 10.223.128.204, 10.223.128.218"],
    :x-amzn-vpc-id          ["vpc-08466e8fa1fc4d330"],
    :x-amzn-vpce-id         ["vpce-0a42e0e4f6052d2a6"],
    :x-amzn-vpce-config     ["0"],
    :Host                   ["api.lime-dev12.internal.rbigroup.cloud"],
    :Content-Type           ["application/json"],
    :Postman-Token          ["fbfe1147-6459-454d-a6d7-68629a2022e2"],
    :x-amzn-vpce-policy-url ["MQ==;vpce-svc-01fedad8f6ddc0953"],
    :User-Agent             ["PostmanRuntime/7.29.0"],
    :X-Amzn-Trace-Id
    ["Self=1-624c4641-39061598649a512f2b364894;Root=1-624c4641-31a8bde2177ec9aa0409676b"]}})

(deftest has-role-test
  (is (= nil
         (fl/has-role? {:roles []} nil))))

(deftest test-jwt-header
  (let [cmd (assoc dummy-cmd
                   :user {:selected-role :group-2})
        response {:body
                  {:headers
                   {:Access-Control-Allow-Headers  "Id,VersionId,X-Authorization,Content-Type,X-Amz-Date,Authorization,X-Api-Key,X-Amz-Security-Token"
                    :Access-Control-Expose-Headers "*"
                    :Access-Control-Allow-Methods  "OPTIONS,POST,PUT,GET"
                    :Access-Control-Allow-Origin   "*"
                    :Content-Type                  "application/json"}
                   :isBase64Encoded false
                   :body
                   {:source
                    {:commands
                     [{:id     #uuid "c5c4d4df-0570-43c9-a0c5-2df32f3be124"
                       :cmd-id :dummy-cmd}]
                     :user
                     {:selected-role :group-2}}
                    :user {:id    "john.smith@example.com"
                           :roles [:group-1 :group-3 :group-2]
                           :role  :group-2
                           :email "john.smith@example.com"}}
                   :statusCode      200}

                  :method :post
                  :url    "http://mock/2018-06-01/runtime/invocation/00000000-0000-0000-0000-000000000000/response"}]
    (testing
     "Autorization header request"
      (mock-core
       {:env {"Region" "eu-west-1"}}
       (runtime/lambda-requests
        {:edd {:config {:secrets-file "files/secret-eu-west.json"}}}
        (fn [ctx body]
          {:source body
           :user   (get-in ctx [:meta :user])})
        [(authorization-header-request (-> cmd
                                           util/to-json))]

        :filters [fl/from-api])
       (is (= [response]
              (client/traffic-edn)))))

    (testing
     "BASE64 Autorization header request"
      (mock-core
       {:env {"Region" "eu-west-1"}}
       (runtime/lambda-requests
        {:edd {:config {:secrets-file "files/secret-eu-west.json"}}}
        (fn [ctx body]
          {:source body
           :user   (get-in ctx [:meta :user])})
        [(authorization-header-request (-> cmd
                                           util/to-json
                                           util/base64encode)
                                       :isBase64Encoded true)]

        :filters [fl/from-api])
       (is (= [response]
              (client/traffic-edn)))))))

(deftest test-cognito-authorizer
  (let [cmd (assoc dummy-cmd
                   :user {:selected-role :users})
        response {:body
                  {:headers
                   {:Access-Control-Allow-Headers  "Id,VersionId,X-Authorization,Content-Type,X-Amz-Date,Authorization,X-Api-Key,X-Amz-Security-Token"
                    :Access-Control-Expose-Headers "*"
                    :Access-Control-Allow-Methods  "OPTIONS,POST,PUT,GET"
                    :Access-Control-Allow-Origin   "*"
                    :Content-Type                  "application/json"}
                   :isBase64Encoded false
                   :body
                   {:source
                    {:commands
                     [{:id     #uuid "c5c4d4df-0570-43c9-a0c5-2df32f3be124"
                       :cmd-id :dummy-cmd}]
                     :user
                     {:selected-role :users}}
                    :user {:id "rbi-glms-m2m-prod@rbi.cloud",
                           :roles [:users],
                           :email "rbi-glms-m2m-prod@rbi.cloud",
                           :role :users}}
                   :statusCode      200}

                  :method :post
                  :url    "http://mock/2018-06-01/runtime/invocation/00000000-0000-0000-0000-000000000000/response"}]
    (testing
     "Cogniro authorizer request"
      (mock-core
       {:env {"Region" "eu-west-1"}}
       (runtime/lambda-requests
        {:edd {:config {:secrets-file "files/secret-eu-west.json"}}}
        (fn [ctx body]
          {:source body
           :user   (get-in ctx [:meta :user])})
        [(cognito-authorizer-request (-> cmd
                                         util/to-json))]

        :filters [fl/from-api])
       (is (= [response]
              (client/traffic-edn)))))

    (testing
     "BASE64 Cogniro authorizer request"
      (mock-core
       {:env {"Region" "eu-west-1"}}
       (runtime/lambda-requests
        {:edd {:config {:secrets-file "files/secret-eu-west.json"}}}
        (fn [ctx body]
          {:source body
           :user   (get-in ctx [:meta :user])})
        [(cognito-authorizer-request (-> cmd
                                         util/to-json
                                         util/base64encode)
                                     :isBase64Encoded true)]

        :filters [fl/from-api])
       (is (= [response]
              (client/traffic-edn)))))))

(deftest test-custom-authorizer
  (let [cmd (assoc dummy-cmd
                   :user {:selected-role :admins})
        response {:body
                  {:headers
                   {:Access-Control-Allow-Headers  "Id,VersionId,X-Authorization,Content-Type,X-Amz-Date,Authorization,X-Api-Key,X-Amz-Security-Token"
                    :Access-Control-Expose-Headers "*"
                    :Access-Control-Allow-Methods  "OPTIONS,POST,PUT,GET"
                    :Access-Control-Allow-Origin   "*"
                    :Content-Type                  "application/json"}
                   :isBase64Encoded false
                   :body
                   {:source
                    {:commands
                     [{:id     #uuid "c5c4d4df-0570-43c9-a0c5-2df32f3be124"
                       :cmd-id :dummy-cmd}]
                     :user
                     {:selected-role :admins}}
                    :user {:id "rbi-glms-m2m-prod@rbi.cloud",
                           :roles [:admins],
                           :email "rbi-glms-m2m-prod@rbi.cloud",
                           :role :admins}}
                   :statusCode      200}

                  :method :post
                  :url    "http://mock/2018-06-01/runtime/invocation/00000000-0000-0000-0000-000000000000/response"}]
    (testing
     "Custom authorizer request"
      (mock-core
       {:env {"Region" "eu-west-1"}}
       (runtime/lambda-requests
        {:edd {:config {:secrets-file "files/secret-eu-west.json"}}}
        (fn [ctx body]
          {:source body
           :user   (get-in ctx [:meta :user])})
        [(custom-authorizer-request (-> cmd
                                        util/to-json))]

        :filters [fl/from-api])
       (is (= [response]
              (client/traffic-edn)))))

    (testing
     "If we add from-bucket filter it is ignored"
      (mock-core
       {:env {"Region" "eu-west-1"}}
       (runtime/lambda-requests
        {:edd {:config {:secrets-file "files/secret-eu-west.json"}}}
        (fn [ctx body]
          {:source body
           :user   (get-in ctx [:meta :user])})
        [(custom-authorizer-request (-> cmd
                                        util/to-json))]

        :filters [fl/from-api fl/from-bucket])
       (is (= [response]
              (client/traffic-edn)))))

    (testing
     "BASE64 Custom authorizer request"
      (mock-core
       {:env {"Region" "eu-west-1"}}
       (runtime/lambda-requests
        {:edd {:config {:secrets-file "files/secret-eu-west.json"}}}
        (fn [ctx body]
          {:source body
           :user   (get-in ctx [:meta :user])})
        [(custom-authorizer-request (-> cmd
                                        util/to-json))]

        :filters [fl/from-api])
       (is (= [response]
              (client/traffic-edn)))))))

