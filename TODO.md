# TODO

## Summary Generation

* Response summary should always be generated for proper logging and tracing.

* postgres-event and view store should setup DB by using register. We should not fill in manually DB parameters. Same is for opensearch. We need to try opening connection on startup in background. In tests and other places we should nowhere be parsing explicitly DB connections. I register something that needs postgres and this impl should take care . 


NOW: 
- SQL support multiple partitions for history table
- register method must accept second parameter optional with config. Add malli schema with propper documentation for each parameter
- 
- Dont do upsert in postgres view postgres-event
- Version 0 handling  sould also never happen
- rename parameter to query (defmethod get-snapshot [ctx query]) must be a map
- check we are not fetching env variables but using ctx 


Remove :meta from events in :peek-state

## Test fixtures on the runtime

- `edd.test.fixture.dal`: one-argument helpers, `(mock/handle-cmd cmd)`, `(mock/query query)`,
  `(mock/apply-cmd cmd)`, running on the runtime `with-mock-dal` started from its `:ctx`. Today the
  helpers still take the ctx per call.
- `edd.test.fixture.dal`: every helper call should be an invocation of its own with its own
  `:invocation-id` (request-id comes from the test, the scope stays one per `with-mock-dal`). Not done:
  events then carry `:meta {:invocation-id ..}`, which breaks `:keep-meta` assertions, among them
  `doc.edd.command-test/keep-meta-fixture`.
- `lambda.test.fixture.core/mock-core`: rework to start the Lambda runtime once
  (`aws.lambda/init`) and invoke events on it (`aws.lambda/handle-event`) instead of running the
  runtime API loop for every test; the tests then assert on the response, not on the recorded
  `/runtime/invocation` traffic.
- `edd.test.fixture.execution` has its own effect queue (`edd.memory.event-store/peek-cmd!`) next to
  the command store `mock/execute-fx` uses; keep one.
- `with-mock-dal` redefines `edd.common/create-identity` and `edd.el.query/handle-query` globally
  (`with-redefs`), so other threads do not see the mocks.
