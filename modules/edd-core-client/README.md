# edd-core-client

HTTP calls to other edd-core services and the HTTP resolver for remote deps.

```clojure
(edd.client.deps/register ctx {:credentials (aws.cognito/credentials)})
```

- Remote `:deps` (with `:service`) POST to `{ApiUrl}/private/{test|prod}/{service}/query`
  (`ApiUrl` defaults to `https://api.{PrivateHostedZoneName}`); `:ref` deps load
  from the content service. Override with `:query-url (fn [service meta])` and
  `:rtf-url (fn [service query])`.
- Credentials providers are `{:init (fn [ctx] state) :authorize (fn [ctx state request])}`:
  `:init` runs once at startup, `:authorize` adds headers to every request.
  `edd.client.credentials/env` (the default) sends the `ServiceToken` env var;
  `aws.cognito/credentials` sends the service's Cognito token, cached until it expires.
- `edd.client.core` (`call-service`, `query-service`, `get-by-id`) uses the same
  credentials.
