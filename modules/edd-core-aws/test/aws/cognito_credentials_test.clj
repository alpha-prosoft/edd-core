(ns aws.cognito-credentials-test
  (:require [aws.cognito :as sut]
            [clojure.test :refer [deftest is testing]]
            [lambda.util :as util]
            [sdk.aws.cognito-idp :as cognito-idp]))

(defn- authorize
  "Authorizes a request at time now-ms, tokens issued by issue (fn of no
   args returning the admin-initiate-auth response)."
  [{:keys [authorize]} ctx state now-ms issue]
  (with-redefs [util/get-current-time-ms (fn [] now-ms)
                cognito-idp/admin-initiate-auth (fn [_ctx]
                                                  (issue))]
    (get-in (authorize ctx state {:headers {"Content-Type" "application/json"}})
            [:headers "X-Authorization"])))

(defn- issuer
  [issued]
  (fn []
    {:AuthenticationResult {:IdToken   (str "token-" (swap! issued inc))
                            :ExpiresIn 3600}}))

(deftest token-is-cached-until-it-expires-test
  (let [credentials
        (sut/credentials)

        state
        ((:init credentials) {})

        issued
        (atom 0)

        minutes
        (fn [n]
          (* (long n) 60 1000))]
    (testing "fetched for the first request"
      (is
       (= "token-1"
          (authorize credentials {} state (minutes 0) (issuer issued)))))

    (testing "reused while valid"
      (is
       (= "token-1"
          (authorize credentials {} state (minutes 50) (issuer issued)))))

    (testing "fetched again shortly before the hour it is valid for ends"
      (is
       (= "token-2"
          (authorize credentials {} state (minutes 56) (issuer issued)))))

    (is
     (= 2
        @issued))))

(deftest ctx-token-wins-test
  (let [credentials
        (sut/credentials)

        state
        ((:init credentials) {})]
    (is
     (= "given"
        (authorize credentials
                   {:id-token "given"}
                   state
                   0
                   (fn []
                     (throw (ex-info "must not fetch" {}))))))))

(deftest failing-login-test
  (let [credentials
        (sut/credentials)

        state
        ((:init credentials) {})]
    (is
     (thrown-with-msg? clojure.lang.ExceptionInfo #"Unable to get a Cognito token"
                       (authorize credentials {} state 0 (fn []
                                                           {:error {:message "denied"}}))))))
