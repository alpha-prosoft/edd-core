(ns aws.cognito-test
  (:require [clojure.test :refer [deftest is]]
            [aws.cognito :as cognito]))

(def claims
  {:cognito:groups ["lime-risk-managers"
                    "lime-account-managers"
                    "lime-limit-managers"
                    "roles-admin"
                    "realm-test"
                    "units-rbhu"
                    "units-ho"
                    "roles-verifiers"]
   :email          "john.smith@rbinternational.com"
   :exp            1647513070
   :sub            "000d0c9d-d5f1-4cbd-adb2-fa9480be2346"
   :token_use      "id"})

(deftest test-extract-attrs
  (is (= {:department      "DEV1"
          :department-code "dep-code"}
         (cognito/extract-attrs {:id                       "za"
                                 :groups                   [:g]
                                 :x-department             "DEV1"
                                 :custom:x-department_code "dep-code"}))))

(deftest extract-user-test
  (is (= {:email "john.smith@rbinternational.com"
          :id "john.smith@rbinternational.com"
          :realm :test
          :units [:ho :rbhu]
          :roles [:verifiers
                  :admin
                  :lime-limit-managers
                  :lime-account-managers
                  :lime-risk-managers]}
         (cognito/extract-user {} claims))))

(deftest parse-authorizer-user-test
  (is (= {:email "john.smith@rbinternational.com"
          :id "john.smith@rbinternational.com"
          :realm :test
          :role :verifiers
          :roles '(:verifiers
                   :admin
                   :lime-limit-managers
                   :lime-account-managers
                   :lime-risk-managers)
          :units '(:ho
                   :rbhu)}
         (cognito/parse-authorizer-user {} claims)))
  (is (thrown? Exception
               (cognito/parse-authorizer-user {:body {:user {:selected-role :non-existing-role}}} claims))))

(deftest extract-user-test-2
  (let [claims {:id             ""
                :email          ""
                :cognito:groups ["non-interactive" "realm-test"]}]
    (is (= {:email ""
            :id    ""
            :realm :test
            :roles [:non-interactive]}
           (cognito/extract-user {} claims)))
    (is (= {:email ""
            :id    ""
            :realm :test
            :role :non-existing-role
            :roles [:non-interactive]}
           (cognito/parse-authorizer-user {:body {:user {:selected-role :non-existing-role}}} claims)))))

(deftest extract-user-with-department
  (let [claims {:department      "dep"
                :department-code "dcode"
                :email           "john@example.com"}]
    (is (= {:department      "dep"
            :department-code "dcode"}
           (cognito/extract-attrs claims)))

    (is (= {:department-code "dcode"}
           (cognito/extract-attrs {:department_code "dcode"})))))

