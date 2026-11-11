# edd-core-flyway

Runs database migrations when the runtime starts, for services that own their
database: a monolith on Undertow. Services whose database is migrated by their
deployment (Lambda) do not register it.

```clojure
(edd.flyway/register ctx)
(edd.flyway/register ctx {:placeholders {"historyPartitionCount" "4"}})
```

Components declare what they need with `edd.ctx/put-migration` when they
register; none of them depends on Flyway:

| Component | SQL | Schema |
|---|---|---|
| `edd.postgres.event-store` | `sql/files/edd` | `<realm>` |
| `edd.view-store.postgres.view-store` | `edd/view_store/postgres/migrations` | `<realm>_<service>` |
| `edd.postgres.distributor` | `edd/postgres/distributor/migrations` | `<realm>_distributor` |

The feature migrates every declared set for every realm of `:realms` on ctx
(also from `CustomConfig`), `[:prod]` when there is none. Each schema keeps its
own Flyway history, and the database user needs `CREATE` on the database.

Your own component declares its migrations the same way:

```clojure
(edd.ctx/put-migration ctx {:id :my/tables
                            :location "classpath:my/migrations"
                            :schema (fn [ctx realm] (str (name realm) "_my"))})
```
