# edd-core-undertow

Long-running runtime for edd-core services. `edd.undertow.core/start` has the
same contract as the Lambda custom runtime, so one registration serves both:

```clojure
(undertow/start ctx edd/handler)
```

- HTTP requests go through `lambda.http` like API Gateway requests on Lambda:
  the user comes from the authentication feature (e.g. `aws.cognito/register`).
  `Port`/`Host` env vars, default `0.0.0.0:8080`.
- Responses are distributed to the router like on Lambda, through the
  registered distributor (`edd.distribution`).
- The service's command and event sources are consumed, one record at a time,
  through the same `:filters` (runtime filters, see `lambda.runtime`). `:consume` overrides the list,
  empty disables it.
- Features renew what expires for every invocation (`:invocation-ctx`); the AWS registrations keep
  temporary credentials fresh that way, every 30 minutes.
