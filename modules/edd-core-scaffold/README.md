# edd-core-scaffold

Generates a working edd-core service: it compiles, passes its own tests and
runs on the chosen runtime.

```bash
clojure -Sdeps '{:deps {com.alpha-prosoft/edd-core-scaffold {:mvn/version "2.340.0"}}}' \
  -M -m edd.scaffold my-svc
```

## Options

Missing options are asked interactively; questions with a single valid answer
are skipped.

| Option | Values |
|---|---|
| `--runtime` | `lambda` (Java runtime, SnapStart), `undertow`, or both: `lambda,undertow` |
| `--distributor` | `sqs`, `kafka` (Undertow only) |
| `--event-store` | `dynamodb`, `postgres`, `memory` |
| `--view-store` | `s3`, `postgres` (needs the postgres event store), `elastic`, `memory` |
| `--deploy` | `ansible` (Lambda only), `none` |
| `--dir` | target directory, default `./<project-name>` |
| `--version` / `--group-id` | edd-core version and maven group of the generated deps |
| `--edd-core-root` | `:local/root` deps on a local edd-core checkout |
| `--force` | write into a non-empty directory |

```bash
... -M -m edd.scaffold my-svc --runtime undertow --distributor kafka \
    --event-store postgres --view-store postgres --deploy none
```

## Generated project

- `service.clj` registers the distributor, stores, Cognito authentication
  (`auth.clj`), the HTTP deps resolver with Cognito credentials and security.
- `module.clj` registers the domain; `item.clj` is an example command, event
  and query with `:auth` roles and `item_test.clj` its tests.
- `filters.clj` holds the runtime filters (none by default).
- `main.clj` is the entry point per runtime: `lambda.Handler` on Lambda,
  `clojure -M -m my-svc.main` on Undertow (`-Dedd.runtime` picks the runtime
  when both are generated).
- `Dockerfile`, `format.sh`, `tests.edn`, and `ansible/deploy` with `--deploy ansible`.

## Versioning and templates

`build.sh` stamps `resources/edd/scaffold/version.edn` with the build version,
so generated deps match the edd-core release the scaffold comes from.

Templates live in `resources/edd/scaffold/template`: `base` is always copied,
`undertow` and `deploy-<id>` add files per choice and `main/` holds the entry
point per runtime selection; `projectname` is replaced by the project name.
The `it` suite generates three representative combinations and runs their
tests, and both e2e runs render `ping-svc` from this template.
