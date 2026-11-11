# edd-core-undertow

Long-running runtime for edd-core services. `edd.undertow.core/start` has the
same contract as the Lambda custom runtime, so one registration serves both:

```clojure
(undertow/start ctx edd/handler)
```

- HTTP requests go through `lambda.http` like API Gateway requests on Lambda:
  the user comes from an authentication filter (e.g. `aws.cognito/authenticate`).
  `Port`/`Host` env vars, default `0.0.0.0:8080`.
- Responses are distributed to the router like on Lambda, through the
  registered distributor (`edd.distribution`).
- The service's command and event sources are consumed, one record at a time,
  through the same `:filters` (runtime filters, see `lambda.runtime`). `:consume` overrides the list,
  empty disables it.
- Features renew what expires for every invocation (`:invocation-ctx`); the AWS registrations keep
  temporary credentials fresh that way, every 30 minutes.

## Authentication with a JWT

`edd.undertow.jwt/authenticate` is a runtime filter for services behind an identity provider (Keycloak,
Entra ID, Auth0, ...): an HTTP request runs as the user of its token.

```clojure
(undertow/start ctx edd/handler
                :filters [(jwt/authenticate
                           {:discovery-url "https://idp.example.com/realms/acme/.well-known/openid-configuration"
                            :audience "my-api"
                            :claims->user (fn [claims]
                                            {:id (:email claims)
                                             :roles (mapv keyword (:groups claims))
                                             :realm :prod})})])
```

- The token is the `X-Authorization` header, or the bearer token of `Authorization`.
- `:discovery-url` is the OpenID configuration of the provider and names its keys and its issuer;
  `:jwks-url` gives the keys directly and `:issuer` overrides the issuer. Keys are fetched when the first
  request arrives and cached (`:jwks-cache-hours`, 12); an unknown key id is looked up again.
- A token has to be signed by one of those keys (RS256/384/512, ES256/384/512), not expired
  (`:leeway-seconds`, 30), from the issuer and, with `:audience` (one or several), for it. Anything else
  answers `Not authenticated`.
- `:claims->user` gets the verified claims and returns the user that goes to `[:meta :user]`: `:id` is
  required, `:roles`, `:role`, `:realm` and anything else are yours. Without `:role` the request picks one
  of the `:roles` with `{:user {:selected-role :editor}}`; else the user acts as `:non-interactive` when it
  has that role, else in its first role that is not `:anonymous` or a `realm-*` marker, and as
  `:anonymous` when it has none (the same rules as Cognito, `lambda.http/select-role`). The realm is the
  user's `:realm` or `[:auth :default-realm]` of ctx.
- The default takes `:id` from `email`, `preferred_username` or `sub`, `:roles` from `roles` or `groups`
  and `:realm` from `realm`.

## Listeners

`start` listens on `:port`/`:host`, the default listener, which serves edd requests. `:listeners` names more,
one Undertow server each in the same JVM, all on one shared worker pool (`:io-threads`, `:worker-threads`,
by default Undertow's own sizes):

```clojure
(undertow/start ctx edd/handler
                :listeners {:default {:port 8081 :host "127.0.0.1"}
                            :public {:port 8000 :handler public-handler :max-entity-size 1048576}
                            :internal {:port 8001 :handler internal-handler}})
```

- `:default` is the edd listener; `:port`/`:host` alone mean `{:default {:port … :host …}}`. It may name its
  own `:handler`; `:default false` starts no default listener.
- Every other listener names its `:port` and a `:handler`, `(fn [runtime exchange])`. It is called on a worker
  thread with blocking IO started and gets the `HttpServerExchange` as Undertow made it: the handler reads the
  body, sets headers and cookies and streams its response itself. A listener without a handler is refused.
- A listener may set `:host` (default `0.0.0.0`), `:max-entity-size` (bytes; a larger body answers 413) and
  `:request-parse-timeout`, `:no-request-timeout`, `:read-timeout` (milliseconds).
- `(undertow/port runtime)` is the bound port of the default listener, or of the only one;
  `(undertow/port runtime :public)` that of a named one. `stop` stops every listener and the consumers, which
  start once per runtime.

A handler serves edd requests next to its own with the two functions the default listener is made of:

```clojure
(defn public-handler
  [runtime ^HttpServerExchange exchange]
  (if (= "/api/command" (.getRequestPath exchange))
    (undertow/handle-edd runtime exchange)
    (serve-own exchange)))

(undertow/handle-request runtime {:path "/query"
                                  :httpMethod "POST"
                                  :headers {:x-authorization token}
                                  :body (util/to-json request)})
```

- `(handle-edd runtime exchange)` answers the exchange as the default listener does.
- `(handle-request runtime request)` runs a request in the shape `lambda.http` handles in-process and returns
  its response `{:statusCode :headers :body :isBase64Encoded}`, with the same authentication, filters and
  distribution; for callers without an exchange (workers, a service calling itself). What `lambda.http`
  doesn't answer itself (a failed distribution) is thrown, where `handle-edd` answers 500.
