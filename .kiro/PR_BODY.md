# feat: operability quick-wins

## Summary

Delivers five additive operability capabilities to the Search Engine Aggregator Service without touching the core domain or scoring logic. All existing endpoint contracts, ArchUnit rules, and cross-cutting concerns (rate limit, structured logs, error envelopes) remain unchanged.

## Requirements Checklist

### REQ 1 — Application metrics via Micrometer + Prometheus
- [x] 1.1 `/actuator/prometheus` exposed, returns HTTP 200 with `text/plain; version=0.0.4`
- [x] 1.2 `provider_fetch_duration_seconds` Timer (tags: `provider`, `outcome`)
- [x] 1.3 `provider_fetch_failures_total` Counter (tag: `provider`)
- [x] 1.4 `search_query_duration_seconds` Timer (tag: `cache_hit`)
- [x] 1.5 `search_cache_hits_total` / `search_cache_misses_total` Counters
- [x] 1.6 `ingest_items_inserted_total` / `ingest_items_updated_total` / `ingest_items_rejected_total` Counters (tags: `provider`, `reason`)
- [x] 1.7 `ratelimit_blocked_total` Counter (tag: `path`)
- [x] 1.8 No PII in metric tags (verified by jqwik property test)
- [x] 1.9 Graceful 404 when `prometheus` endpoint is excluded from exposure

### REQ 2 — Provider health endpoint
- [x] 2.1 `GET /api/v1/admin/providers` → 200 JSON envelope with per-provider state
- [x] 2.2 Never-synced providers show null timestamps and zero counters
- [x] 2.3 Synced providers carry ISO-8601 `lastSyncAt`, outcome, item count, running totals
- [x] 2.4 Failed providers include sanitized `lastErrorMessage` (max 256 chars)
- [x] 2.5 Rate-limit filter applies to admin endpoints

### REQ 3 — Manual sync trigger endpoint
- [x] 3.1 `POST /api/v1/admin/sync` → 202 Accepted, delegates to async executor
- [x] 3.2 Concurrent guard: second call returns `alreadyRunning: true`
- [x] 3.3 Unexpected exceptions → 500 `INTERNAL_ERROR` envelope
- [x] 3.4 Cache eviction fires after async sync completes

### REQ 4 — Search analytics persistence
- [x] 4.1 Successful searches persist `SearchAnalyticsRecord` with all specified fields
- [x] 4.2 4xx validation errors do NOT persist a record
- [x] 4.3 5xx errors persist a record with `errorCode` and `totalResults=null`
- [x] 4.4 Analytics failure is fire-and-forget (logs warning, search still succeeds)
- [x] 4.5 Sink selection via `analytics.search.sink` (`db` | `log` | `none`); Flyway `V2__search_analytics.sql` for DB mode
- [x] 4.6 `analytics.search.enabled=false` disables all analytics work

### REQ 5 — CSV / JSON export endpoint
- [x] 5.1 `GET /api/v1/search.csv` → UTF-8 BOM, RFC 4180, `Content-Disposition: attachment`
- [x] 5.2 `GET /api/v1/search.json` → JSON array, `Content-Disposition: attachment`
- [x] 5.3 Default limit=100; limit > `export.search.max-rows` (default 1000) → 400 `INVALID_QUERY`
- [x] 5.4 CSV header: `id,title,type,score,publishedAt`; score formatted `%.1f`
- [x] 5.5 Dashboard renders two "Download" links (CSV + JSON) preserving active params
- [x] 5.6 Rate-limit and request-id filters apply to export endpoints

### REQ 6 — Supply-chain hygiene: Renovate
- [x] 6.1 `renovate.json` extends `config:base`, Monday schedule
- [x] 6.2 Non-major updates grouped into single weekly PR
- [x] 6.3 Spring Boot major bumps get separate PR with `breaking` label, no automerge
- [x] 6.4 Self-contained config (no org-specific references)

### REQ 7 — Supply-chain hygiene: Trivy container scan
- [x] 7.1 CI workflow includes Trivy scan against `searchengine:ci` image
- [x] 7.2 HIGH/CRITICAL findings fail the workflow (`exit-code: 1`)
- [x] 7.3 LOW/MEDIUM only → pass + upload `trivy-report.sarif` artifact
- [x] 7.4 Works on fork PRs (no org-secret dependency)
- [x] 7.5 CI also runs `mvnw -B verify -DskipITs`

### Non-functional requirements
- [x] NF-1 Backwards compatibility — no existing endpoint contract changes
- [x] NF-2 Architectural compliance — ArchUnit rules pass (`mvnw verify`)
- [x] NF-3 Observability — all new endpoints covered by `RequestIdFilter`, `GlobalExceptionHandler`, structured logs
- [x] NF-4 Security — admin endpoints protected by `AdminAuthFilter` (`X-Admin-Token` / `ADMIN_API_TOKEN`)
- [x] NF-5 Test strategy — unit tests, property tests (jqwik), and integration tests (Testcontainers) per requirement

## Test Coverage

| Layer | Files added/modified |
|-------|---------------------|
| Unit (`*Test.java`) | Metrics, AdminAuth, ProviderHealthRegistry, SyncCoordinator, Analytics sinks, ExportController, SearchController |
| Property (`*PropertyTest.java`) | MetricsPiiPropertyTest, CsvRoundTripPropertyTest, ClientIpHasherPropertyTest, SyncCoordinatorConcurrencyPropertyTest |
| Integration (`*IT.java`) | AdminProviderControllerIT, AdminSyncControllerIT, ExportControllerIT, JdbcSearchAnalyticsSinkIT |

## How to verify

```bash
# Unit + property + ArchUnit tests
mvnw.cmd -B test

# Full verify (needs Docker for Testcontainers)
mvnw.cmd -B verify

# Skip integration tests if Docker unavailable
mvnw.cmd -B verify -DskipITs

# Local smoke test
docker compose up --build
# Then run scripts/smoke-test.ps1 or scripts/smoke-test.sh
```

## New environment variables

| Variable | Default | Description |
|----------|---------|-------------|
| `ADMIN_API_TOKEN` | _(none, disables admin)_ | Shared token for `/api/v1/admin/*` endpoints |
| `ANALYTICS_ENABLED` | `true` | Enable/disable search analytics |
| `ANALYTICS_SINK` | `db` | Analytics sink: `db`, `log`, or `none` |
| `EXPORT_MAX_ROWS` | `1000` | Max rows per CSV/JSON export request |
