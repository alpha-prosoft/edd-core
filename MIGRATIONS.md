# Migration Guide

- `mock/query` no longer wraps response in `{:result ...}`
- Service name is now a keyword
- S3 `presign-url` changes:
  - New 2-arity `(presign-url ctx object)` accepts ctx with `:aws` key and an object map:
    ```clojure
    (s3/presign-url ctx {:method "PUT"
                         :expires 360
                         :object {:s3 {:bucket {:name "my-bucket"}
                                       :object {:key "path/to/file"}}}
                         :md5 "..."           ;; optional
                         :sha256 "..."        ;; optional
                         :content-length 123}) ;; optional
    ```
  - Additional headers (`content-md5`, `x-amz-content-sha256`, `content-length`) can now be signed via the object map or the `:headers` option in the lower-level arities
  - `ring.util.codec/form-encode` replaced with `sdk.aws.common/aws-form-encode` (ring dependency removed)
  - Host resolution now uses `get-host`, supporting custom S3 endpoints
  - Leading slashes in object paths are stripped automatically to avoid double-slash URLs
- `peek-state`, `verify-state`, and other accessor functions from `edd.test.fixture.dal` now strip `:meta` from results by default. Set `:keep-meta true` in mock state to preserve it.

## Distribution (effects and router messages)

Queue transport moved out of edd-core. Every edd service, the router included, registers a transport,
otherwise every request to `edd.core/handler` (or router publish) fails with `No distributor registered`.
Runtimes no longer check it at startup, so own handlers run without one.

- SQS (previous behaviour): add `com.alpha-prosoft/edd-core-sqs`, call `(edd.sqs.distributor/register ctx)`.
  Queues with a `MaximumMessageSize` below 1 MiB need `SqsMaxMessageSize` (or `{:max-message-size n}`).
- Kafka: add `com.alpha-prosoft/edd-core-kafka`, `(edd.kafka.distributor/register ctx)`, set
  `KafkaBootstrapServers`; run on Undertow, the router also calls `(edd.router.core/register ctx)`.
- Transports are features (`edd.distribution/register-transport`) and name their destinations; other
  features can wrap publishing with `:publish-filter (fn [ctx {:keys [destination messages]} chain])`.
- `edd.router.util` / `edd.router.s3-handler` are gone (fan-out is `edd.distribution.router`).
- Tests: `mock/ctx` registers the in-memory transport (destinations `router`, `<svc>.<stage>.commands`);
  a hand-built ctx passed to a runtime needs `(edd.distribution.memory/register ctx)`. Read
  router messages with `(edd.distribution.memory/published ctx)` instead of redefining `sqs/sqs-publish`.
- Tests of the request path no longer need the Lambda fixture (`lambda.test.fixture.core/mock-core`, now in
  edd-core-aws): `edd.test.fixture.runtime` runs API requests and queue records on the in-memory runtime
  `lambda.runtime.memory`, see README "Testing".

## Test fixtures run on the runtime

`mock/handle-cmd`, `mock/apply-cmd`, `mock/apply-events` and `mock/query` keep their arguments and
results but send their request through the dispatch of `edd.core/handler`, on an in-memory runtime
`with-mock-dal` starts. Request ids stay optional and exceptions are still thrown. What differs:

- a query result that is a map with an `:error` key comes back as `{:error ..}` only;
- a command with more than 25 breadcrumbs returns `{:error :loop-detected}`;
- `(mock/with-mock-dal {:ctx (my/register mock/ctx) ..} ..)` starts the stores and the runtime from that
  ctx instead of `mock/ctx`.

Runtimes share `lambda.runtime/start` (ctx with handler, filters, configuration and initialized
features) and `lambda.runtime/with-invocation` (the scope of one invocation); a custom runtime uses
them instead of calling `lambda.runtime/init` and `edd.ctx/init-features` itself.

`io.forward/yaml` is no longer a dependency of edd-core; a service using `yaml.core` declares it.

## DANGER: two applied SQL migrations changed

Two migration files that are already applied in existing databases were edited, so their Flyway
checksums no longer match. A validated `flyway migrate` fails on those databases with a checksum
mismatch until they are repaired (`flyway repair`) or baselined. Nothing has to be re-applied, the
resulting schema is the same.

- Event store `sql/files/edd/V010__Indices.sql`: used the schema `test` by name, now
  `${flyway:defaultSchema}`. It failed in every database without a `test` schema.
- View store `V003__Prewarm_Aggregate_Indexes.sql`: required `pg_cron` and `pg_prewarm`, now schedules
  the prewarm only where both are available and is a no-op elsewhere (plain Postgres).

The view store migrations moved inside the module, from `modules/edd-core-view-store-postgres/migrations`
to `modules/edd-core-view-store-postgres/resources/edd/view_store/postgres/migrations`, so they ship in
its jar; deployments reading the old folder need the new path (the module's Ansible role is updated).
The event store migrations stay in `sql/files/edd` and are on the classpath of edd-core as `files/edd`.

## Migrations at startup

Components declare the migrations they need with `edd.ctx/put-migration`: the Postgres event store,
the Postgres view store and the Postgres distributor do. A service that owns its database registers
`(edd.flyway/register ctx)` (module `edd-core-flyway`), which runs every declared set with Flyway when
the runtime starts, for every realm of `:realms` on ctx (`[:prod]` when there is none). Services whose
database is migrated by their deployment do not register it and nothing changes for them.

## Postgres distributor

New transport for a single service on the Postgres event store, without router and queues: module
`edd-core-distributor-postgres`, `(edd.postgres.distributor/register ctx)`, Undertow only. Its outbox
is a schema of its own per realm, `<realm>_distributor`; the migration is declared like the others, so
`edd.flyway/register` creates it at startup. It polls the realms of `:realms` on ctx. Markers that
failed `:max-attempts` times keep `failed_at` and `last_error`; clear `failed_at` to have them
delivered again.

Transports may take part in the transaction storing a command response: `:on-store (fn [ctx resp])` of
`edd.distribution/register-transport`, called by the Postgres event store.

## Repeated requests are marked

A command response for a request-id that was seen before carries
`:meta {:handler {:attempt n :cached true}}` next to `:result`: `:attempt` is the number of earlier
tries from the request log (Postgres and memory event store; DynamoDB does not count), `:cached` is
set when the stored response was returned instead of running the command. A first execution is
unchanged. Custom event stores return the count from `dal/log-request` (or nil).

## Response cache is optional

edd-core no longer stores every response in S3 on its own. Without a response cache the router messages
carry the events and effects inline, split to fit the transport, and a duplicate request rebuilds them
from the stored response summary and the command store (`dal/get-response-effects`, implement it for
custom event stores).

- To keep the S3 cache register it: `(edd.response.s3/register ctx)` (edd-core-aws, same bucket and
  keys as before). Do this for services with big responses: a response that does not fit one router
  message is stored in parts of `edd.el.ctx/set-effect-partition-size` effects (default 10000), one
  part in memory at a time, the router messages carry only the S3 references and a duplicate request
  sends them again.
- A response fits one router message when it has at most one part of effects and is within the
  transport message size (`SqsMaxMessageSize`, default 1 MiB); it is then sent inline, without S3.
  Lower the two thresholds to send more through S3.
- An SQS router reads the S3 references on its own. A router on another transport registers the cache
  too.
- `:response-cache` and `edd.response.cache/register-default` are gone; a custom cache registers with
  `edd.response.cache/register`.
- Router messages carry only `id`, `meta`, `request-id` and `interaction-id` of each event.

## AWS moved to edd-core-aws

Add `com.alpha-prosoft/edd-core-aws`; namespaces keep their names:
`aws.*`, `sdk.aws.*`, `lambda.jwt`, `edd.dynamodb.event-store`, `edd.s3.view-store`,
`edd.aws-emf.core` (edd-core-aws-emf is merged into it), the Lambda runtime and `lambda.test.fixture.core`.

- HTTP requests need an authentication feature: `(aws.cognito/register ctx)` restores the Cognito
  behaviour. Cognito helpers
  (`extract-user`, `parse-authorizer-user`, `check-user-role`, `extract-attrs`) moved to `aws.cognito`;
  `from-bucket` and `parse-key` moved to `aws.filters`.
- `edd.core/fetch-from-s3` is gone; transports resolve big bodies themselves (`:unwrap`).
- `AWS_LAMBDA_DISABLE_METRICS` still works, `DisableMetrics` is the neutral name.
- `lambda.core/start`, `:edd/runtime` and `-Dedd.runtime` are gone: a service calls the start of the
  runtimes it runs on, e.g. `(edd.undertow.core/start ctx edd.core/handler)` or
  `(aws.lambda/lambda-custom-runtime ctx edd.core/handler)`.
- `edd.schema.swagger` is gone, and with it schema generation by running the service main with
  `-Dedd.runtime=edd.schema.swagger/swagger-runtime`; image builds doing that produce no `schema.json`.
- AWS configuration and credentials are a feature, `aws.ctx/register`, which the AWS registrations
  (stores, SQS distributor, Cognito, S3 caches) bring along; it resolves them at startup and keeps
  temporary credentials fresh on Undertow. A service using AWS only through its own code registers it
  itself. Undertow no longer looks for edd-core-aws on the classpath.
- `aws.aws/get-token`, `get-token-from-cache`, `admin-auth` and `get-or-set` are gone, the service
  token comes from `aws.cognito/credentials`. The `edd-core-dev` module is removed.
- A custom runtime builds its ctx with `lambda.runtime/start`, as the bundled runtimes do.

## Runtime filters

The runtimes decode their input themselves: API Gateway / HTTP requests (`lambda.http`), SQS records
and the queue records of Undertow consumers, one record per request. Startup fails with a pointer here
while the old options are used.

- Drop `lambda.filters/from-api`, `from-queue` and `:post-filter lambda.filters/to-api`; `lambda.filters`
  is gone (`parse-query-string`, `get-realm`, `assign-metadata` are in `lambda.http`).
- Filters are `(fn [ctx request chain])` around the handler, first one outermost: call
  `(chain ctx request)` to proceed and post-process its response, or answer without calling it.
  `{:cond :fn :init}` maps must be rewritten, `:init` goes to a feature.
- `aws.filters/from-bucket` is such a filter: `:filters [aws.filters/from-bucket]`.
- An API filter that set the user itself becomes a feature providing `:resolve-user (fn [ctx] user)`.
- `edd.core/handler` handles one request; SQS batches reach it record by record
  (`lambda.runtime/handle-records`), `edd.core/prepare-response` and `filter-queue-request` are gone.
- `edd.router.core/handler` routes one router message.
- An SQS batch is handled record by record; `from-queue` used to handle only its first record.
- ctx no longer has `:query-string-parameters` and `:lambda-api-requiest`, and `:req` is set for HTTP
  requests only (`(get-in ctx [:req :queryStringParameters])`); check handlers and filters reading them.
- API commands keep the authenticated `:user` and `:realm`: request `:meta` can no longer override
  them, the rest of `:meta` is passed on as before.
- The pipeline is `lambda.runtime`; `aws.lambda` only has the Lambda loop (`lambda-custom-runtime`,
  `handle-event`). Its `apply-filters`, `handle-request`, `send-response`, `init-filters` and
  `aws.aws/send-success`, `send-error`, `get-items-to-delete`, `enqueue-response` are gone.

## Remote deps need a deps resolver

Remote `:deps` (with `:service`) are resolved by the registered deps resolver; a service with remote
deps and no resolver fails at startup.

- Add `com.alpha-prosoft/edd-core-client` (no AWS dependency any more) and register the HTTP resolver
  with Cognito credentials, as before: `(edd.client.deps/register ctx {:credentials (aws.cognito/credentials)})`.
  The Cognito token is now cached until it expires instead of per request. Without `:credentials` the
  token comes from the `ServiceToken` env var.
- `edd.el.query/calc-service-query-url`, `calc-service-rtf-url` and `-http-call` moved to
  `edd.client.deps` (`query-url`, `rtf-url`).
- Tests: `mock/ctx` resolves remote deps from the `:deps` mocks (`mock/deps-resolver`), so dep calls no
  longer appear in `client/verify-traffic`. A test ctx not built from `mock/ctx` registers it with
  `(edd/reg-deps-resolver ctx {:resolver mock/deps-resolver})`.

## OpenSearch moved to edd-core-opensearch

- Add `com.alpha-prosoft/edd-core-opensearch` for `edd.elastic.view-store` and `lambda.elastic`.
- Requests are SigV4 signed by default; `IndexDomainAuth=none` for clusters without AWS signing.
- The elastic and postgres view stores no longer write aggregates to S3 on their own. To keep it
  register the S3 snapshot cache after the view store:
  `(edd.s3.view-store/register-snapshot-cache ctx)` (edd-core-aws). Aggregates are then written to S3
  on every update and snapshots and older versions are read from there, as before. Without it reads of
  an older aggregate version return nil, except from the postgres view store with history enabled.
