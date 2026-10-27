(ns lambda.runtime-test
  (:require [clojure.test :refer [deftest is testing]]
            [lambda.runtime :as sut]))

(defn- echo-handler
  [_ctx request]
  {:handled request})

(deftest filters-wrap-the-handler-test
  (let [trace
        (atom [])

        tracing
        (fn [label]
          (fn [ctx request chain]
            (swap! trace conj [:before label])
            (let [response
                  (chain ctx (update request :path (fnil conj []) label))]
              (swap! trace conj [:after label])
              (assoc response label true))))

        ctx
        (sut/init {} echo-handler {:filters [(tracing :outer) (tracing :inner)]})]
    (is
     (= {:handled {:path [:outer :inner]}
         :outer true
         :inner true}
        (sut/handle ctx {})))

    (is
     (= [[:before :outer] [:before :inner] [:after :inner] [:after :outer]]
        @trace))))

(deftest filter-short-circuits-test
  (let [ctx
        (sut/init {} echo-handler {:filters [(fn [_ctx _request _chain]
                                               {:error "denied"})]})]
    (is
     (= {:error "denied"}
        (sut/handle ctx {})))))

(deftest exceptions-become-error-responses-test
  (let [ctx
        (sut/init {} (fn [_ _] (throw (ex-info "boom" {:reason :test}))) {})]
    (is
     (= {:exception {:message {:reason :test}}}
        (sut/handle ctx {})))))

(deftest removed-options-fail-at-startup-test
  (testing ":post-filter"
    (is
     (thrown-with-msg? clojure.lang.ExceptionInfo #":post-filter was removed"
                       (sut/init {} echo-handler {:post-filter identity}))))

  (testing "{:cond :fn} filters"
    (is
     (thrown-with-msg? clojure.lang.ExceptionInfo #"Filters are \(fn \[ctx request chain\]\)"
                       (sut/init {} echo-handler {:filters [{:cond (constantly true) :fn identity}]})))))

(deftest records-stop-at-first-failure-test
  (let [ctx
        (sut/init {}
                  (fn [_ctx {:keys [n]}]
                    (if (= 2 n)
                      (throw (ex-info "failed" {}))
                      {:n n}))
                  {})]
    (is
     (= [{:n 1} {:exception {:message {}}}]
        (sut/handle-records ctx [{:body "{\"n\":1}"} {:body "{\"n\":2}"} {:body "{\"n\":3}"}])))))
