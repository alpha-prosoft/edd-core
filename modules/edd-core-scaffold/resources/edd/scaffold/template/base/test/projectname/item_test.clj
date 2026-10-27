(ns projectname.item-test
  (:require [clojure.test :refer [deftest is]]
            [edd.test.fixture.dal :as mock]
            [lambda.uuid :as uuid]
            [projectname.service :as service]))

(defn- as-role
  [role]
  (assoc (service/register mock/ctx)
         :meta {:realm :test
                :user {:id "tester"
                       :role role}}))

(deftest create-item-test
  (mock/with-mock-dal
    (let [id
          (uuid/gen)

          ctx
          (as-role :admin)

          result
          (mock/handle-cmd ctx {:cmd-id :create-item
                                :id id
                                :attrs {:name "first"}})]

      (is
       (= {:success true
           :events 1
           :effects []
           :identities 0
           :meta [{:create-item {:id id}}]}
          result))

      (mock/verify-state
       :event-store
       [{:event-id :item-created
         :id id
         :attrs {:name "first"}
         :event-seq 1
         :meta {:user {:id "tester"
                       :role :admin}}}])

      (is
       (= {:id id
           :name "first"
           :status :created
           :version 1}
          (mock/query ctx {:query-id :get-item
                           :id id}))))))

(deftest unknown-role-is-denied-test
  (mock/with-mock-dal
    (let [result
          (mock/handle-cmd (as-role :guest) {:cmd-id :create-item
                                             :id (uuid/gen)
                                             :attrs {:name "first"}})]

      (is
       (= {:message "Forbidden"
           :cmd-id :create-item
           :role :guest
           :policy :implicit}
          (:error result)))

      (mock/verify-state
       :event-store
       []))))
