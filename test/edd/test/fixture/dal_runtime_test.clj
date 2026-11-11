(ns edd.test.fixture.dal-runtime-test
  (:require [clojure.test :refer [deftest is testing]]
            [edd.core :as edd]
            [edd.test.fixture.dal :as mock]
            [lambda.uuid :as uuid])
  (:import [clojure.lang ExceptionInfo]))

(defn- register
  [ctx]
  (edd/reg-cmd
   (edd/reg-cmd ctx :make (fn [_ctx cmd]
                            {:event-id :made
                             :id (:id cmd)}))
   :break (fn [_ctx cmd]
            (throw (ex-info "Broken on purpose" {:id (:id cmd)})))))

(deftest runtime-starts-from-given-ctx-test
  (let [ctx
        (register (assoc mock/ctx :service-name :given-svc))]
    (mock/with-mock-dal
      {:ctx ctx}

      (is
       (= :given-svc
          (:service-name mock/*runtime*)))

      (is
       (= {:success true
           :events 1}
          (select-keys (mock/handle-cmd ctx {:cmd-id :make
                                             :id (uuid/gen)})
                       [:success :events]))))))

(deftest runtime-starts-from-mock-ctx-test
  (mock/with-mock-dal
    (is
     (= (:service-name mock/ctx)
        (:service-name mock/*runtime*)))))

(deftest handler-exception-is-thrown-test
  (mock/with-mock-dal
    (testing "the exception of the handler reaches the test as it was thrown"
      (is
       (thrown-with-msg? ExceptionInfo
                         #"Broken on purpose"
                         (mock/handle-cmd (register mock/ctx) {:cmd-id :break
                                                               :id (uuid/gen)}))))))
