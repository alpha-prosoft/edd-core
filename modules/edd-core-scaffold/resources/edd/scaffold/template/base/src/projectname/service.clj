(ns projectname.service
  (:require [edd.memory.event-store :as event-store]
            [edd.memory.view-store :as view-store]
            [edd.sqs.distributor :as distributor]
            [aws.cognito :as cognito]
            [edd.client.deps :as deps]
            [projectname.auth :as auth]
            [edd.security :as security]
            [projectname.module :as module]))

(defn register
  [ctx]
  (security/register (module/register ctx)))

(defn init
  [ctx]
  (let [named
        (assoc ctx :service-name :projectname)

        with-distributor
        (distributor/register named)

        with-event-store
        (event-store/register with-distributor)

        with-view-store
        (view-store/register with-event-store)

        with-auth
        (auth/register with-view-store)

        with-deps-resolver
        (deps/register with-auth {:credentials (cognito/credentials)})]
    (register with-deps-resolver)))
