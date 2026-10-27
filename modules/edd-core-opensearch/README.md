# edd-core-opensearch

OpenSearch / Elasticsearch view store (`edd.elastic.view-store`) and client
(`lambda.elastic`).

```clojure
(edd.elastic.view-store/register ctx)
```

Configured from `IndexDomainEndpoint`, `IndexDomainScheme` (default `https`) and
`IndexDomainAuth`: `sigv4` (default, Amazon OpenSearch Service) or `none`.
