# edd-core-distributor-postgres

Distributor for a single service on the Postgres event store: no router, no
queues. Events and effects of a command are delivered to the service itself
from an outbox in the same database.

```clojure
(edd.postgres.distributor/register (edd.flyway/register ctx))
```

- The event store writes an outbox marker in the transaction of every command
  with events or effects; a poller per realm delivers it: an apply per
  aggregate and the commands of every effect, rebuilt from the stored response.
- An effect for another service fails the command, nothing is stored.
- A failed delivery is retried with backoff; after `:max-attempts` (any
  number, counted in the outbox) the marker stays with `failed_at` and
  `last_error` set. Clear `failed_at` to deliver it
  again.
- The outbox lives in its own schema per realm, `<realm>_distributor`. The
  module declares its migration; `edd.flyway/register` (edd-core-flyway)
  applies it when the runtime starts, or the deployment runs
  `edd/postgres/distributor/migrations` from this module.
- It polls the realms of `:realms` on ctx, `[:prod]` when there is none.
- Needs the Postgres event store and a long-running runtime (Undertow).
- `(edd.postgres.distributor/pending? ctx realm)` tells whether something still
  waits for delivery, for callers that read their own writes.

Options: `:poll-interval-ms` (5000), `:batch-size` (10), `:lease-seconds` (60),
`:max-attempts` (10).
