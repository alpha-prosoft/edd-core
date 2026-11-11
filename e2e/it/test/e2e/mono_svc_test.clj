(ns e2e.mono-svc-test
  "mono-svc: one scaffolded service on Undertow + Postgres with the
   Postgres distributor, no router and no queues. Local stack only
   (e2e/run-e2e-local.sh); run-e2e.sh skips ^:local."
  (:require [clojure.test :refer [deftest is testing]]
            [e2e.api-test :as api]
            [lambda.uuid :as uuid]))

(deftest ^:local effect-chain-through-the-outbox
  (let [id
        (uuid/gen)

        resp
        (api/cmd! "mono-svc" :start-chain id {:steps 3})]

    (is
     (api/success? resp))

    (testing "every effect is delivered to the service itself and writes the next one"
      (let [agg
            (api/wait-for #(= 3 (:step %))
                          #(api/aggregate "mono-svc" id))]

        (is
         (= 3
            (:step agg)))))

    (testing ":advance allows only :non-interactive, so the chain proves effects run as that user"
      (let [denied
            (api/cmd! "mono-svc" :advance id {:step 9 :steps 9})]

        (is
         (= "Forbidden"
            (get-in denied [:body :error :message])))))))

(deftest ^:local effect-for-another-service-fails-the-command
  (let [id
        (uuid/gen)

        resp
        (api/cmd! "mono-svc" :call-remote id {})]

    (is
     (not (api/success? resp)))

    (is
     (re-find #"delivers to :mono-svc only"
              (str (:body resp))))

    (testing "nothing was stored"
      (is
       (nil?
        (api/aggregate "mono-svc" id))))))
