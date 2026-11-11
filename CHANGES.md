# Changelog

## Changes

**[AP-1035]** Authentication as runtime filter, Undertow listeners
- authentication is a runtime filter in the ordered :filters vector:
  (lambda.http/authenticate resolve-user), aws.cognito/authenticate
  for Cognito; requests from other sources pass through it
- the runtime no longer resolves the user, it refuses an HTTP
  request that reaches the handler without one
- :resolve-user features are removed and fail at registration,
  lambda.http/assign-metadata is replaced by with-user
- scaffold, e2e services and edd.test.fixture.runtime authenticate
  through the filter
- edd-core-undertow: :listeners starts more HTTP listeners with their
  own handlers next to the edd one, on a shared worker pool;
  handle-edd and handle-request serve edd requests from them
- edd-core-undertow: edd.undertow.jwt/authenticate verifies the JWT
  of any identity provider against its JWKS (discovery or JWKS URL),
  :claims->user turns the verified claims into the user
- queue records get their user from a filter: edd.kafka.filters,
  edd.sqs.filters, edd.postgres.distributor.filters authenticate, or
  lambda.runtime/authenticate-records for every record; by default
  the service's :non-interactive user; the record is in ctx :record
- lambda.http/select-role holds the role rules (selected role,
  :non-interactive, :anonymous) for Cognito and the JWT filter
- see MIGRATIONS.md

**[AP-1032]** Cleanup testing and scafolding
- edd-core-distributor-postgres: transport for a single service on
  the Postgres event store, effects and events are delivered to the
  service itself from an outbox, no router and no queues
- edd-core-flyway: components declare their migrations
  (edd.ctx/put-migration), the feature runs them at startup for the
  realms of :realms on ctx; the event store, view store and
  distributor declare theirs
- DANGER: applied migrations V010 (event store) and V003 (view store)
  changed, existing databases need a flyway repair or baseline
- lambda.core, :edd/runtime and edd.schema.swagger removed, a service
  starts its runtimes itself; io.forward/yaml is no longer a
  dependency
- runtimes share lambda.runtime/start and with-invocation
- edd.test.fixture.dal runs its requests on an in-memory runtime
  through the dispatch of edd.core/handler, with-mock-dal takes :ctx
- scaffold: --config applies a JSON project definition, --save-config
  writes the one prompting produced; postgres distributor choice
- local e2e adds mono-svc, scaffolded on the Postgres distributor
- see MIGRATIONS.md

**[AP-1021]** Add scaffold and move AWS out of core
- edd-core-scaffold: one-line CLI that generates a working service
  (lambda/undertow, sqs/kafka, stores, optional ansible deploy),
  security wired in; e2e ping-svc is rendered from it
- distribution: transports are features (memory in core,
  edd-core-sqs, edd-core-kafka), router fan-out moved to core
- response cache dropped: responses travel inline, duplicates are
  rebuilt from the event and command stores
- edd-core-aws (Lambda runtime, DynamoDB, S3, Cognito, EMF) and
  edd-core-opensearch split out of core, new edd-core-undertow
- runtimes decode their input (lambda.http, queue records); filters
  are (fn [ctx request chain]), :post-filter removed
- remote deps go through a registered deps resolver; edd-core-client
  is the HTTP one with pluggable credentials (env, Cognito)
- ping/pong e2e also runs on Undertow + Kafka + Postgres in docker
  compose (e2e profile), same e2e/it suite as on AWS
- see MIGRATIONS.md
- API commands keep the authenticated :user and :realm; a client can
  no longer override them through the request :meta

**[AP-982]** Add e2e tests for edd features

**[AP-976]** Add features extension point
We can use it to implement general extensions
for edd-core application. We use security as
 referece implementation

**[AP-946]** Fix compliance to run for all stores

**[AP-937]** Fix credentails caching

**[AP-929]** Fix token caching

**[AP-923]** Fix token expiration check

**[AP-922]** Lazily load handler
Because we dont want to run java handler when we import
namespace (test  break)

**[AP-897]** Add audit metadata to events and aggregates
- improve startup of java lambda

**[AP-887]** Add logging on error

**[AP-882]** Add createdOn for dynamodb
Fix response on api should not throw because API
gw does not propagate response

**[AP-860]** Cache context on startup

**[AP-856]** Improve starup performance

**[AP-826]** Fix order of names (typo)

**[AP-825]** Fix stack name to support realms

**[AP-824]** Validate events before storing

**[AP-823]** Throw exception on test failure

**[CARS-5758]** Log command validation failures

**[CARS-5783]** Add missing Snappy reflection-config entry
- allows the Parquet writer to use :snappy compression in native-image

**[CARS-5710]** Fix schema for uuid extension
- transfer the uuid extension from test to public

**[CARS-5584]** Add d-metrics macro with invocation timing and EMF submodule

**[CARS-5422]** Improve :keep-meta stripping in test fixture
Simplify normalize-item implementation with a single strippable-keys set.
Expose root-meta-fields and meta-meta-fields for test assertions.
Add comprehensive stripping test covering nil/false, true, and vector modes.

**[AP-819]** Add view store compliance tests
Implement compliance test suite for get-snapshot and update-aggregate

- operations across S3, Memory and Postgres view stores.
- tests covering snapshots, updates, versioning, realm isolation

**[CARS-5295]** Better SQL logging with args
- add logging sql args for the execute function

**[CARS-5148]** Parallel Parquet writer and perf fixes
- make write-parquet-bytes optionally parallel if a realized collection
  is passed and row count is sufficiently large
- optimize writing sparse optional values
- disable dictionary encoding for high-cardinality columns
- disable statistics
- precompute some schema objects
- add parquet writer benchmark with JIT disabled to simulate
  native-image

**[CARS-5178]** Extended parquet metadata
- table.schema attribute is added, db table schema name

**[CARS-5169]** Replace some inline values with params
- replace inline HoneySQL expressions for better planning
- update tests

**[CARS-5133]** Perf fixes to parquet writer

**[CARS-5102]** Fix edd-core advanced search
- fix empty where clause in adv search

**[CARS-5048]** Add edd-core-parquet module
- add write support for Parquet bytes
- update Makefile format to add Ivan-style binding formatting

**[CARS-5008]** Improve advanced search
- update expression that builds advanced search query
- handle case when id is matched against a string
- fix and update tests

**[AP-800]** Implement test suite for event store compliance

**[CARS-4941]** Rework edd-core attributes
- update dimension attributes
- add tests for the new attrs

**[AP-798]** Remove :sequence feature
This commit removes the :sequence concept from all edd-core event store implementations.
The sequence feature allowed event handlers to returna
{:sequence :keyword :id aggregate-id} maps to generate
gapless incrementing sequence numbers with bidirectional
lookup between aggregate IDs and sequence numbers.
The feature requires distributed locking and complex two-phase commits
in Postgres (using LOCK TABLE and dual-table updates).

This cannot be efficiently or reliably implemented in DynamoDB or other
eventually-consistent data stores without significant complexity and
potential for race conditions. It is also easy to replace with simple
aggregate that will used as counter (testable, and more flexible)

**[CARS-4852]** Refactor edd-core attributes file (intenal)

**[CARS-4853]** Update gitleaks configuration to TOML
Format with fingerprint-based allowlist. Secrets
all are anyway public and we didi history rewrite

**[CARS-4670]** Add edd-client submodule
- add edd.client.core namespace to reach other services

**[CARS-4437]** Fix empty context in edd core
- replace deps map with a full context
- update tests

**[AP-795]** Fix build to work properly

**[CARS-4540]** Update gitleaks for test secrets

**[CARS-4404]** Increase loop depth support

**[CARS-4340]** Add some functions to the edd core io module
- add minor io functions
- add tests
- update changes file

**[LSO-16349]** Fix typo in identity query

**[CARS-4101]** Add better logging to have durations
This way we can see what is bottleneck.

**[CARS-3906]** Allow endpoint override for all operations

**[LSP-10890]** Load lambda.logging explicitly
We should ensure it is AOT compiled if needed on runtime

**[CARS-3085]** Disable metrics locally with an env var
- add env var condition in lambda core
- update readme

**[CARS-3081]** Handle exceptions properly
To tel lambnda function that message was not successfully
processes we need to throw exception because it will
not bre re-tried.

**[LSP-10872]** Fix malli parser in edd-postgres module
- bump malli to the latest version
- mitigate new parsing response (remove tags)

**[LSO-16510]** Implement logging for local de
Add simple hierarchical loogger. Each d-time will be logged in its on
ident

**[LSO-16507]** Add a comment to the parser module
- add a comment to the parser module about malli
- update makefile

**[LSP-10813]** Improve edd-core (errors, logs, metrics)
FEATURE:
- disable metrics then an env var set
- detailed log in command handler

FIX:
- for java runtime we need new *request* atom for each request

**[LSP-10688]** Reduce dependencies in json module
- exclude external libraries from the net.mikera/vectorz library
- additional make targets
- run the changes script earlier in pre-build.sh

**[LSP-10680]** Create edd-json module

**[LSO-15555]** Remove deps to ring
We dont need ring and want to use URLEncoder from
JVM. But there are special requirements for AWS
url encoding:

https://docs.aws.amazon.com/IAM/latest/UserGuide/reference_sigv-create-signed-request.html#sigv4-create-canonical-request-steps

**[LSO-15825]** Update compojure to 1.7.1
- move compojure to edd-core-dev/deps.edn
- leave explicit requirement for ring-codec in main deps.edn

**[LSO-15805]** Initialitize runtime only once

**[LSO-15727]** Fix cache creation for CRAC Java Runtime

**[LSO-15556]** Unify runtimes
We want to make sure that we can use CRAC (SnapStart) and keep all
existing functionality
