(ns edd.el.query-test
  (:require [clojure.test :refer [deftest is]]
            [edd.core :as edd]
            [edd.test.fixture.runtime :as api]
            [edd.test.fixture.dal :as mock]
            [lambda.uuid :as uuid]))

(defn- register
  []
  (edd/reg-query mock/ctx
                 :get-meta
                 (fn [ctx _query]
                   (select-keys (:meta ctx) [:realm :some-other-stuff :user]))))

(defn- query-meta
  [api-user request-meta]
  (mock/with-mock-dal
    (let [response
          (api/post (api/start (register) :user api-user)
                    {:request-id     (uuid/gen)
                     :interaction-id (uuid/gen)
                     :meta           request-meta
                     :query          {:query-id :get-meta}})]
      (get-in response [:body :result]))))

(def request-meta
  {:realm            :realm12
   :some-other-stuff :yes
   :user             {:id    "admin@example.com"
                      :email "admin@example.com"
                      :roles [:admin :read-only]
                      :role  :admin}})

(deftest test-if-meta-is-resolved-to-query
  (is
   (= request-meta
      (query-meta {:id    ""
                   :email ""
                   :roles [:non-interactive]
                   :role  :non-interactive
                   :realm :test}
                  request-meta))))

(deftest test-interactive-user-cannot-override-user-and-realm
  (is
   (= {:realm            :realm11
       :some-other-stuff :yes
       :user             {:id    "am@example.com"
                          :email "am@example.com"
                          :roles [:account-manager]
                          :role  :account-manager}}
      (query-meta {:id    "am@example.com"
                   :email "am@example.com"
                   :roles [:account-manager]
                   :role  :account-manager
                   :realm :realm11}
                  request-meta))))
