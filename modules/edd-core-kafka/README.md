# edd-core-kafka

Apache Kafka distributor.

```clojure
(edd.kafka.distributor/register ctx {:bootstrap-servers "127.0.0.1:9092"})
```

`:bootstrap-servers` falls back to the `KafkaBootstrapServers` env var. Extra
Kafka properties go under `:producer` and `:consumer`.

- Topics are `{env}.router` and `{env}.{stage}.{service}.commands|events`
  (stage from the realm), dead letters go to `<topic>.dlq`. Run the router on
  the Undertow runtime with `(edd.router.core/register ctx)` so it consumes the
  router topic.
- Messages are limited to 1 MB; there is no offload of bigger bodies.
- The message key is the group id, keeping per-aggregate order.
- `(edd.kafka.filters/authenticate)` in `:filters` fills in the user of consumed records, by default the
  service's `:non-interactive` user; `:resolve-user (fn [ctx request])` picks another, the record is in
  ctx `:record`.
- Consumers use the service name as group id and commit each record after it
  succeeds; a failed record is re-delivered.
- `docker compose up kafka kafka-ui` starts a broker on 9092 and a UI on 8090;
  the `it` suite and `make e2e-local` run against it.
