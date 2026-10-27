(ns aws.ctx-register-test
  (:require [aws.ctx :as sut]
            [clojure.test :refer [deftest is testing]]
            [edd.ctx :as edd-ctx]))

(defn- started
  "ctx after startup, credentials resolved by resolve (a fn of no args)."
  [ctx resolve]
  (with-redefs [sut/resolve-credentials (fn [_preloaded]
                                          (resolve))]
    (edd-ctx/init-features (sut/register ctx))))

(deftest registers-once-test
  (is
   (= [:edd/aws]
      (map :feature-id (edd-ctx/get-features (sut/register (sut/register {})))))))

(deftest resolved-credentials-are-renewed-test
  (let [issued
        (atom 0)

        resolve
        (fn []
          {:aws-access-key-id     (str "key-" (swap! issued inc))
           :aws-secret-access-key "secret"
           :aws-session-token     "token"})

        ctx
        (started {} resolve)

        key-now
        (fn []
          (with-redefs [sut/resolve-credentials (fn [_preloaded]
                                                  (resolve))]
            (get-in (edd-ctx/invocation-ctx ctx) [:aws :aws-access-key-id])))]
    (testing "resolved at startup"
      (is
       (= "key-1"
          (get-in ctx [:aws :aws-access-key-id]))))

    (testing "kept while they are fresh"
      (is
       (= "key-1"
          (key-now))))

    (testing "resolved again after 30 minutes"
      (with-redefs [sut/credentials-age-ms (fn [_fetched-at]
                                             (* 31 60 1000))]
        (is
         (= "key-2"
            (key-now)))))))

(deftest explicit-credentials-are-kept-test
  (let [ctx
        (started {:aws {:aws-access-key-id     "given"
                        :aws-secret-access-key "secret"
                        :aws-session-token     "token"}}
                 (fn []
                   {:aws-access-key-id     "resolved"
                    :aws-secret-access-key "secret"}))]
    (with-redefs [sut/credentials-age-ms (fn [_fetched-at]
                                           (* 31 60 1000))]
      (is
       (= "given"
          (get-in (edd-ctx/invocation-ctx ctx) [:aws :aws-access-key-id]))))))
