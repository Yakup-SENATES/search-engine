# Requirements Document

## Introduction

The Search Engine Aggregator Service is functionally complete and stable, but
it lacks the operational visibility and minor product affordances expected
from a production-leaning service. This feature delivers five **quick-win**
capabilities that close that gap without touching the core domain or scoring
logic:

1. **Application metrics + dashboards** via Micrometer + Prometheus, with
   structured counters and timers for the existing operational concerns
   (provider fetch, cache, search, ingest, rate limit).
2. **Provider health endpoint + manual sync trigger** behind an
   admin-prefixed REST API so operators can inspect provider state and force
   a sync without waiting for the scheduler.
3. **Search analytics** — every search query is recorded with its parameters,
   latency, total-results count, and cache-hit flag for later analysis.
4. **CSV / JSON export** — a paginated CSV / JSON download endpoint that
   reuses the existing search pipeline.
5. **Supply-chain hygiene** — a Renovate config and a Trivy container scan
   step in CI so dependency drift and image vulnerabilities are surfaced
   automatically.

The feature is **additive**:

- The pure-Java `domain.scoring` package and its ArchUnit guards stay
  untouched (REQ 22.2).
- No existing endpoint contract changes; new endpoints live under
  `/api/v1/admin/*` and `/api/v1/search.csv`.
- Existing tolerant-parsing, error-envelope, rate-limit, and structured-log
  guarantees are preserved.

## Glossary

- **MetricsRegistry**: The Spring-managed `MeterRegistry` bean (Micrometer)
  that backs `/actuator/prometheus`.
- **PrometheusEndpoint**: The Spring Boot Actuator endpoint at
  `GET /actuator/prometheus` exposing meters in the Prometheus text format.
- **AdminPathPrefix**: The constant URI prefix `/api/v1/admin/` reserved
  for operator-only endpoints introduced by this feature.
- **ProviderHealthResponse**: JSON DTO returned by the provider-health
  endpoint, listing each registered `ContentProvider` and its last-sync
  outcome.
- **ManualSyncResponse**: JSON DTO returned by the manual-sync endpoint
  reporting whether a sync was triggered, was skipped (already running), or
  failed.
- **SearchAnalyticsRecord**: The structured record persisted (DB row or
  log line) for each `GET /api/v1/search` call.
- **CsvExportFormat**: The `text/csv; charset=UTF-8` media type used by
  the CSV export endpoint.
- **JsonExportFormat**: The `application/json; charset=UTF-8` media type
  used by the JSON export endpoint.
- **CSV_MAX_ROWS**: Configurable upper bound on the number of rows a single
  export call may return (default 1000).
- **RenovateConfig**: A `renovate.json` file at the repository root that
  configures Mend Renovate to open weekly dependency-update PRs.
- **TrivyScanJob**: A GitHub Actions job that runs `aquasecurity/trivy`
  against the built Docker image and fails the workflow on `HIGH` /
  `CRITICAL` findings.

## Requirements

### Requirement 1: Application metrics via Micrometer + Prometheus

**User Story:** As an operator, I want the service to emit structured
metrics in the Prometheus text format, so that I can build dashboards and
alerts on traffic, latency, error, and cache behavior.

#### Acceptance Criteria

1. WHEN the application starts THEN the Spring Boot Actuator's
   PrometheusEndpoint SHALL be exposed at `GET /actuator/prometheus` and
   return HTTP 200 with `Content-Type: text/plain; version=0.0.4`.
2. WHEN any provider's `fetch()` completes (successfully or exceptionally)
   THEN a `provider_fetch_duration_seconds` Timer SHALL record the elapsed
   wall-clock time, tagged with `provider={name}` and
   `outcome={success|failure}`.
3. WHEN a provider fetch fails (any exception, including
   `RestClientException`) THEN a `provider_fetch_failures_total` Counter
   SHALL be incremented with the same `provider` tag.
4. WHEN the `DefaultSearchService.search` method completes THEN a
   `search_query_duration_seconds` Timer SHALL record its elapsed time,
   tagged with `cache_hit={true|false}` (the value is captured by the cache
   manager via `ResilientCacheManager`'s `Cache.ValueWrapper get()` return).
5. WHEN the search cache returns a hit THEN a `search_cache_hits_total`
   Counter SHALL be incremented; on a miss, `search_cache_misses_total`
   SHALL be incremented.
6. WHEN `DefaultContentAggregator.runSync` finishes a single provider's
   batch THEN three Counters SHALL be updated:
   `ingest_items_inserted_total`, `ingest_items_updated_total`, and
   `ingest_items_rejected_total{reason=...}`, each tagged with
   `provider={name}`.
7. WHEN the rate-limit filter rejects a request THEN a
   `ratelimit_blocked_total` Counter SHALL be incremented, tagged with
   `path={apiPathPrefix}`.
8. WHEN the `prometheus` endpoint is scraped THEN no end-user PII (no `q`
   strings, no IP addresses, no `requestId` values) SHALL appear in metric
   tags or names.
9. WHEN the operator overrides
   `management.endpoints.web.exposure.include` to a value that excludes
   `prometheus` THEN `GET /actuator/prometheus` SHALL return HTTP 404 and
   the application SHALL still start successfully.

### Requirement 2: Provider health endpoint

**User Story:** As an operator, I want a single REST endpoint that reports
the live state of every registered provider, so that I can diagnose ingest
problems without reading log files.

#### Acceptance Criteria

1. WHEN `GET /api/v1/admin/providers` is called THEN the response SHALL
   have HTTP 200, `Content-Type: application/json`, and a JSON envelope
   `{ "providers": [ ... ] }` with one element per registered
   `ContentProvider` bean (REQ 1.1, REQ 22.4).
2. WHEN a provider has never been synced THEN its element SHALL include
   `"name": "...", "lastSyncAt": null, "lastSyncOutcome": null,
   "lastFetchedItems": 0, "totalSuccesses": 0, "totalFailures": 0`.
3. WHEN a provider has at least one completed sync THEN its element SHALL
   carry `"lastSyncAt"` as an ISO-8601 instant in UTC,
   `"lastSyncOutcome"` ∈ `{ "success", "failure" }`,
   `"lastFetchedItems"` as a non-negative integer, and the running
   `totalSuccesses` / `totalFailures` counters.
4. WHEN a provider's most recent sync failed THEN its element SHALL include
   a `"lastErrorMessage"` field carrying the **sanitized** exception
   summary (max 256 characters; secrets redacted by the existing
   `ErrorMessageSanitizer`).
5. WHEN this endpoint is invoked THEN the existing rate-limit filter
   SHALL apply unchanged (the path begins with `/api/v1/`).

### Requirement 3: Manual sync trigger endpoint

**User Story:** As an operator, I want to be able to force an immediate
sync without waiting for the scheduler, so that I can validate provider
configuration and verify recently published content.

#### Acceptance Criteria

1. WHEN `POST /api/v1/admin/sync` is called THEN the controller SHALL
   delegate to `ContentAggregator.runSync()` on a non-request thread and
   return HTTP 202 Accepted with a JSON body
   `{ "triggered": true, "alreadyRunning": false }`.
2. WHEN a previous manual or scheduled sync is still in progress THEN the
   endpoint SHALL return HTTP 202 with
   `{ "triggered": false, "alreadyRunning": true }` and SHALL NOT start
   a second concurrent run (matches the existing `AtomicBoolean running`
   guard in `SyncScheduler`).
3. WHEN the manual sync raises an unexpected exception THEN the endpoint
   SHALL log it via the existing structured-log path and return the
   standardized 500 `INTERNAL_ERROR` envelope.
4. WHEN the sync completes (asynchronously) THEN the existing
   `@CacheEvict(allEntries=true)` on `runSync` SHALL fire so the search
   cache is invalidated (REQ 11.4 / 12.4).

### Requirement 4: Search analytics persistence

**User Story:** As a product owner, I want every search request recorded
with its parameters and latency, so that I can identify zero-result
queries and tune the scoring formula.

#### Acceptance Criteria

1. WHEN `GET /api/v1/search` returns successfully THEN a
   SearchAnalyticsRecord SHALL be appended with the fields:
   `requestedAt` (ISO-8601 instant), `q`, `type` (nullable), `sort`,
   `page`, `limit`, `totalResults`, `latencyMs`, `cacheHit` (boolean),
   `requestId` (the existing MDC value), and `clientIpHash`
   (SHA-256 hash of the resolved IP, hex-encoded).
2. WHEN the search request fails with a 4xx validation error THEN no
   analytics record SHALL be persisted.
3. WHEN the search request fails with a 5xx error THEN a record SHALL
   still be persisted with `totalResults=null`, `cacheHit=false`, and an
   additional `errorCode` field set to the standardized error code.
4. WHEN the analytics persistence backend is unreachable THEN the
   analytics call SHALL log a warning and the user-facing search response
   SHALL still complete successfully (best-effort, fire-and-forget).
5. WHEN persistence is configured to `db` (default) THEN records SHALL be
   stored in a new `search_analytics` table introduced by a new Flyway
   migration `V2__search_analytics.sql`. WHEN configured to `log` THEN
   records SHALL be emitted as a single structured-log line at INFO with
   the marker `searchAnalytics`. The mode SHALL be selected via the
   `analytics.search.sink` property (`db` | `log` | `none`).
6. WHEN `analytics.search.enabled=false` THEN no analytics work SHALL be
   performed regardless of `sink`.

### Requirement 5: CSV / JSON export endpoint

**User Story:** As a dashboard user, I want to download the current search
result set as CSV or JSON, so that I can share or analyze it offline.

#### Acceptance Criteria

1. WHEN `GET /api/v1/search.csv?q=...&type=...&sort=...&limit=...` is
   called with valid parameters THEN the response SHALL have HTTP 200,
   `Content-Type: text/csv; charset=UTF-8`,
   `Content-Disposition: attachment; filename="search-{yyyyMMdd-HHmmss}.csv"`,
   and a UTF-8 BOM prefix so Excel reads it correctly.
2. WHEN `GET /api/v1/search.json?q=...&type=...&sort=...&limit=...` is
   called with valid parameters THEN the response SHALL have HTTP 200,
   `Content-Type: application/json; charset=UTF-8`,
   `Content-Disposition: attachment; filename="search-{yyyyMMdd-HHmmss}.json"`,
   and a JSON array of objects with the same schema as `data[]` from the
   existing `SearchResponse`.
3. WHEN `limit` is omitted THEN it SHALL default to 100. WHEN `limit` is
   greater than `CSV_MAX_ROWS` (default 1000, configurable via
   `export.search.max-rows`) THEN the response SHALL be HTTP 400 with
   the standardized `INVALID_QUERY` envelope.
4. WHEN the CSV is generated THEN the first row SHALL be the literal
   header `id,title,type,score,publishedAt`. Each subsequent row SHALL
   contain the matching fields, with strings RFC 4180-quoted (double
   quotes doubled inside, fields containing `,`, `"`, or newlines wrapped
   in double quotes), `score` formatted with one decimal place
   (`%.1f`), and `publishedAt` as ISO-8601.
5. WHEN the dashboard is loaded THEN it SHALL render two visible
   "Download" links — one CSV and one JSON — that preserve the active
   `sort` and `type` parameters and a hard-coded `q=*` placeholder when
   the dashboard has no keyword (the dashboard is a top-N view).
6. WHEN the export endpoint is invoked THEN the existing rate-limit and
   request-id MDC filters SHALL apply unchanged.

### Requirement 6: Supply-chain hygiene — Renovate

**User Story:** As a maintainer, I want automated weekly PRs that bump
project dependencies, so that the project stays current without manual
chore work.

#### Acceptance Criteria

1. WHEN a `renovate.json` file is added at the repository root THEN it
   SHALL extend the `config:base` preset and SHALL set
   `"schedule": ["before 9am on monday"]` so PRs land in a predictable
   cadence.
2. WHEN Renovate runs against the repository THEN it SHALL group all
   non-major dependency updates into a single PR per week labeled
   `dependencies` and open separate PRs for major version bumps so they
   can be reviewed individually.
3. WHEN a Spring Boot major version bump is offered THEN Renovate SHALL
   create a PR labeled `breaking` and set `"automerge": false`
   regardless of the global automerge setting.
4. WHEN the project is forked THEN the Renovate configuration SHALL not
   reference any organization-specific settings (i.e. it SHALL be
   self-contained at the repository level).

### Requirement 7: Supply-chain hygiene — Trivy container scan

**User Story:** As a maintainer, I want every CI run to scan the built
Docker image for known vulnerabilities, so that no `HIGH` / `CRITICAL`
issue ships unnoticed.

#### Acceptance Criteria

1. WHEN the GitHub Actions CI workflow runs THEN it SHALL include a
   TrivyScanJob step that executes the
   `aquasecurity/trivy-action@<pinned-sha>` action against the locally
   built `searchengine:ci` image.
2. WHEN Trivy detects any `HIGH` or `CRITICAL` vulnerability THEN the CI
   workflow SHALL fail (`exit-code: 1`).
3. WHEN Trivy detects only `LOW` / `MEDIUM` issues THEN the CI workflow
   SHALL pass and the report SHALL be uploaded as a GitHub Actions
   artifact named `trivy-report.sarif`.
4. WHEN the action runs on a pull request from a fork THEN it SHALL still
   execute (no organization-secret dependency) and SHALL still post the
   SARIF artifact.
5. WHEN no GitHub Actions workflow currently exists THEN this feature
   SHALL also create a minimal workflow at `.github/workflows/ci.yml`
   that runs `mvnw -B verify -DskipITs` followed by the Trivy scan.

## Non-functional requirements

1. **Backwards compatibility** — None of the existing endpoints
   (`GET /api/v1/search`, `GET /dashboard`, `GET /actuator/health`,
   `GET /swagger-ui.html`, `GET /v3/api-docs`) SHALL change their
   response shape.
2. **Architectural compliance** — All new code SHALL respect the existing
   ArchUnit rules: domain remains framework-free, web does not import
   infrastructure, scoring stays pure.
3. **Observability** — All new endpoints SHALL be covered by the existing
   `RequestIdFilter`, `GlobalExceptionHandler`, and structured-log
   pipeline (no bespoke logging or error formats).
4. **Security** — The admin endpoints (`/api/v1/admin/*`) SHALL be
   protected by a single shared API token, validated by a new servlet
   filter; the token SHALL be loaded from `ADMIN_API_TOKEN` and SHALL
   be redacted from logs by the existing `SecretRedactingPropertySource`.
5. **Test strategy** — Each requirement SHALL be covered by at least one
   `*Test.java` (unit) and, where Spring wiring matters
   (Requirements 1, 2, 3, 4 sink=db, 5), an `*IT.java` Testcontainers
   integration test. CSV escaping SHALL also have a `*PropertyTest.java`
   jqwik check (≥100 iterations).
