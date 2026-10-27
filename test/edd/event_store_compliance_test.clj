(ns edd.event-store-compliance-test
  "Memory implementation of the event store compliance suite.
   The shared tests live in edd.compliance.event-store; postgres and dynamodb
   run the same suite as integration tests:
   - edd.postgres.event-store-compliance-it
   - edd.dynamodb.event-store-compliance-it"
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [edd.compliance.event-store :as compliance]
            [edd.dal :as dal]
            [edd.memory.event-store :as event-store]
            [edd.test.fixture.dal :as mock]
            [lambda.uuid :as uuid]))

;;; ============================================================================
;;; Test Setup
;;; ============================================================================

(defn make-memory-ctx
  [& {:keys [realm service-name]
      :or   {realm compliance/test-realm}}]
  (let [base-ctx
        {:service-name           (or service-name
                                     (keyword (str "compliance-" (uuid/gen))))
         :hosted-zone-name       "example.com"
         :environment-name-lower "local"
         :meta                   {:realm realm}}]
    (event-store/register base-ctx)))

(defn with-memory-dal
  [test-fn]
  (mock/with-mock-dal
    (test-fn)))

(use-fixtures :each
  (fn [test-fn]
    (binding [compliance/*ctx-factory* make-memory-ctx
              compliance/*dal-wrapper* with-memory-dal]
      (test-fn))))

;;; ============================================================================
;;; Re-export All Compliance Tests
;;; ============================================================================

;; Category A: Basic Event Storage
(deftest a1-store-single-event
  (compliance/a1-store-single-event))

(deftest a2-store-multiple-events-same-aggregate
  (compliance/a2-store-multiple-events-same-aggregate))

(deftest a3-store-events-different-aggregates
  (compliance/a3-store-events-different-aggregates))

(deftest a4-get-events-by-aggregate-id
  (compliance/a4-get-events-by-aggregate-id))

(deftest a5-get-events-with-version-filter
  (compliance/a5-get-events-with-version-filter))

(deftest a6-get-events-with-version-range
  (compliance/a6-get-events-with-version-range))

;; Category B: Identity Management
(deftest b1-store-single-identity
  (compliance/b1-store-single-identity))

(deftest b2-store-multiple-identities-different-aggregates
  (compliance/b2-store-multiple-identities-different-aggregates))

(deftest b3-get-multiple-identities-bulk
  (compliance/b3-get-multiple-identities-bulk))

(deftest b4-identity-not-found
  (compliance/b4-identity-not-found))

(deftest b5-identity-scoped-to-service
  (compliance/b5-identity-scoped-to-service))

;; Category C: Optimistic Locking
(deftest c1-prevent-duplicate-event-seq
  (compliance/c1-prevent-duplicate-event-seq))

(deftest c2-get-max-event-seq
  (compliance/c2-get-max-event-seq))

(deftest c3-get-max-event-seq-no-events
  (compliance/c3-get-max-event-seq-no-events))

;; Category D: Side Effects Tracking
(deftest d1-store-single-effect
  (compliance/d1-store-single-effect))

(deftest d2-store-multiple-effects
  (compliance/d2-store-multiple-effects))

(deftest d3-effect-with-multiple-commands
  (compliance/d3-effect-with-multiple-commands))

(deftest d4-effects-with-breadcrumbs
  (compliance/d4-effects-with-breadcrumbs))

;; Category E: Request Logging
(deftest e1-log-request
  (compliance/e1-log-request))

(deftest e2-log-request-error
  (compliance/e2-log-request-error))

(deftest e3-log-request-counts-attempts
  (compliance/e3-log-request-counts-attempts))

;; Category F: Response Log & Deduplication
(deftest f1-store-results-logs-response-summary
  (compliance/f1-store-results-logs-response-summary))

(deftest f2-same-request-different-breadcrumbs
  (compliance/f2-same-request-different-breadcrumbs))

(deftest f3-no-cached-response-returns-nil
  (compliance/f3-no-cached-response-returns-nil))

(deftest f4-idempotency-key-request-id-plus-breadcrumbs
  (compliance/f4-idempotency-key-request-id-plus-breadcrumbs))

;; Category G: Interaction Traceability
(deftest g1-all-records-share-interaction-id
  (compliance/g1-all-records-share-interaction-id))

(deftest g2-get-records-filters-by-interaction-id
  (compliance/g2-get-records-filters-by-interaction-id))

(deftest g3-cross-service-interaction-tracking
  (compliance/g3-cross-service-interaction-tracking))

(deftest g4-empty-interaction-returns-empty-results
  (compliance/g4-empty-interaction-returns-empty-results))

;; Category H: Atomic Transactions
(deftest h1-store-results-is-atomic
  (compliance/h1-store-results-is-atomic))

(deftest h2-failure-rolls-back-all-changes
  (compliance/h2-failure-rolls-back-all-changes))

(deftest h3-identity-conflict-rolls-back-and-reports-key
  (compliance/h3-identity-conflict-rolls-back-and-reports-key))

(deftest h4-multiple-aggregates-in-transaction
  (compliance/h4-multiple-aggregates-in-transaction))

;; Category I: Edge Cases
(deftest i1-empty-store-results
  (compliance/i1-empty-store-results))

(deftest i2-large-event-payload
  (compliance/i2-large-event-payload))

(deftest i3-special-characters-in-identity
  (compliance/i3-special-characters-in-identity))

(deftest i4-max-event-seq-consistency
  (compliance/i4-max-event-seq-consistency))

(deftest i5-realm-isolation
  (compliance/i5-realm-isolation))

;; Category J: Retried Requests
(deftest j1-retried-request-redistributes-effects
  (compliance/j1-retried-request-redistributes-effects))

;;; ============================================================================
;;; Memory-specific Observability (mock store internals, not portable)
;;; ============================================================================

(deftest log-request-lands-in-command-log
  (mock/with-mock-dal
    (let [ctx
          (assoc (make-memory-ctx)
                 :request-id (uuid/gen)
                 :interaction-id (uuid/gen)
                 :invocation-id (uuid/gen)
                 :breadcrumbs [0])]
      (dal/log-request ctx {:commands    [{:cmd-id :test-cmd}]
                            :breadcrumbs [0]})

      (is
       (= 1
          (count (mock/peek-state :command-log)))))))

(deftest log-request-error-lands-in-request-error-log
  (mock/with-mock-dal
    (let [ctx
          (assoc (make-memory-ctx)
                 :request-id (uuid/gen)
                 :interaction-id (uuid/gen)
                 :invocation-id (uuid/gen)
                 :breadcrumbs [0])]
      (dal/log-request-error ctx
                             {:breadcrumbs [0]}
                             {:error "Test error"})

      (is
       (= 1
          (count (mock/peek-state :request-error-log)))))))

(deftest store-results-logs-summary-in-response-log
  (mock/with-mock-dal
    (let [ctx
          (assoc (make-memory-ctx)
                 :request-id (uuid/gen)
                 :interaction-id (uuid/gen)
                 :invocation-id (uuid/gen)
                 :breadcrumbs [0])

          summary
          {:success true
           :events  0}

          _
          (dal/store-results (assoc ctx :resp {:events     []
                                               :effects    []
                                               :identities []
                                               :summary    summary}))

          [response :as responses]
          (mock/peek-state :response-log)]
      (is
       (= 1
          (count responses)))

      (is
       (= summary
          (:data response))))))
