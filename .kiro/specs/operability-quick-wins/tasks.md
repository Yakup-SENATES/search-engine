# Implementation Plan — Operability Quick Wins

## Overview

This plan delivers the five quick-win operability features defined in
`requirements.md` and architected in `design.md`. Tasks are sequenced so
each step ends in a green build and earlier infrastructure (metrics,
sync coordinator, admin auth) is available to later sections (analytics,
export). Run `mvnw.cmd -B verify -DskipITs` after every task and the
full `verify` (with Docker) after each section.

Status legend: `[ ]` not started, `[~]` in progress, `[x]` done.

## Tasks

### 1. Branch + scaffold

- [x] 1.1 Create feature branch `feat/operability-quick-wins` from `feat/spec-and-scaffold`.
- [x] 1.2 Add `micrometer-registry-prometheus` runtime dependency to `pom.xml`. Verify `mvnw.cmd -B -DskipTests package` succeeds.
- [x] 1.3 Add the new package skeletons (empty `package-info.java`) under `infrastructure/metrics/`, `infrastructure/admin/`, `infrastructure/sync/`, `infrastructure/analytics/`, `application/analytics/`, `web/api/csv/`. ArchUnit must stay green.

### 2. Metrics — Requirement 1

- [x] 2.1 Add `MetricsConfig` enabling `prometheus` actuator endpoint via YAML; expose `/actuator/prometheus`. Add an IT that asserts HTTP 200 and `Content-Type: text/plain; version=0.0.4`.
- [-] 2.2 Implement `ProviderFetchMetrics` (Timer + Counter); inject and call from `JsonProviderClient` + `XmlProviderClient`. Add unit tests verifying meter names, tags, and sample increments.
- [~] 2.3 Implement `SearchMetrics` (Timer with `cache_hit` tag, hit/miss counters); inject into `DefaultSearchService` and capture hit/miss via `ResilientCacheManager.get(...)` return.
- [~] 2.4 Implement `IngestMetrics` (insert/update/reject counters); inject into `DefaultContentAggregator` and increment per outcome.
- [~] 2.5 Implement `RateLimitMetrics`; increment on rejection in `RateLimitFilter`.
- [~] 2.6 Add a property test `MetricsPiiPropertyTest` asserting no registered meter has a tag value matching the patterns for `q`, `requestId`, or an IPv4/IPv6 literal (jqwik 200 iterations, generates random search queries via the controller and scrapes `/actuator/prometheus`).
- [~] 2.7 README — add a "Metrics" subsection in "Architecture Decisions" listing the meter names + tag schema.

### 3. Sync coordinator + admin endpoints — Requirements 2, 3

- [~] 3.1 Extract `AtomicBoolean running` from `SyncScheduler` into a `SyncCoordinator` `@Component` exposing `tryRun(Runnable)` and `boolean isRunning()`. Update `SyncScheduler` to delegate.
- [~] 3.2 Implement `ProviderHealthRegistry` `@Component` with a `recordSuccess(name, items)` / `recordFailure(name, throwable)` API; inject into `DefaultContentAggregator` next to `IngestMetrics`. Maintain `ConcurrentHashMap<String, ProviderHealthSnapshot>`.
- [~] 3.3 Implement `AdminAuthFilter` (servlet filter at `Ordered.HIGHEST_PRECEDENCE + 50`) validating `X-Admin-Token` against `ADMIN_API_TOKEN`. Mismatch → 401 envelope. Constant-time compare. Skip when `admin.auth.token` is empty (admin surface disabled).
- [~] 3.4 Implement `AdminProviderController` returning the `ProviderHealthRegistry` snapshots as JSON. `ProviderHealthDto` carries the seven public fields from REQ 2.
- [~] 3.5 Implement `AdminSyncController` calling `SyncCoordinator.tryRun(...)` against a Spring `TaskExecutor` bean configured for one worker thread. Return 202 with the boolean envelope.
- [~] 3.6 IT `AdminProviderControllerIT` (auth, payload shape, after one sync run all counters > 0).
- [~] 3.7 IT `AdminSyncControllerIT` (first call triggers, second returns alreadyRunning=true while first is sleeping in a stub provider).

### 4. Search analytics — Requirement 4

- [~] 4.1 Add Flyway migration `src/main/resources/db/migration/V2__search_analytics.sql` with the schema from `design.md`. Verify `mvnw.cmd -B verify` migrates cleanly against a fresh container.
- [~] 4.2 Define `SearchAnalyticsSink` interface, `SearchAnalyticsRecord` value object, and `SearchAnalyticsRecorder` (best-effort wrapper that catches and logs).
- [~] 4.3 Implement the three sinks: `JdbcSearchAnalyticsSink` (uses `JdbcTemplate` to keep us off the JPA layer), `LogSearchAnalyticsSink` (structured KV log line), `NoOpSearchAnalyticsSink`. Wire selection in `AnalyticsConfig` via `analytics.search.sink`.
- [~] 4.4 Hook `SearchAnalyticsRecorder` into `SearchController` after successful search; pass cache-hit flag + resolved client IP. Add a shared `ClientIpHasher` (SHA-256 hex) under `infrastructure/admin/` (reused by analytics).
- [~] 4.5 Hook into `GlobalExceptionHandler` so 5xx errors still get a record with `errorCode`. 4xx validation errors are skipped.
- [~] 4.6 Unit test all three sinks. IT for the JDBC sink against a Testcontainers Postgres.
- [~] 4.7 Property test `ClientIpHasherPropertyTest` asserting determinism + 64-character hex output for arbitrary IPv4/IPv6 strings (jqwik 200 iterations).

### 5. CSV / JSON export — Requirement 5

- [~] 5.1 Implement the pure-Java `Csv` utility under `web/api/csv/` (RFC 4180 escape; quote when comma, quote, CR, or LF appear; double inner quotes).
- [~] 5.2 Property test `CsvRoundTripPropertyTest` (jqwik 200 iterations) verifying parse-after-format round-trip on random string rows.
- [~] 5.3 Implement `ExportController` with `GET /api/v1/search.csv` (StreamingResponseBody, UTF-8 BOM) and `GET /api/v1/search.json`. Reuse `SearchService.search(SearchQuery)` so the existing cache and pagination logic apply.
- [~] 5.4 Add `export.search.max-rows` ConfigurationProperty (default 1000); reject `limit > max-rows` with the `INVALID_QUERY` envelope.
- [~] 5.5 Add two "Download" links to `dashboard.html` preserving `activeSort`/`activeType`; use `q=*` placeholder (per REQ 5.5).
- [~] 5.6 IT `ExportControllerIT` exercising both CSV and JSON paths, asserting headers + content-disposition + first row.

### 6. Documentation — Requirement set rollup

- [~] 6.1 Update `README.md`: API Endpoints adds admin + export rows; Environment Variables adds `ADMIN_API_TOKEN`, `ANALYTICS_ENABLED`, `ANALYTICS_SINK`, `EXPORT_MAX_ROWS`, `MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE`; new "Operability" section linking to `/actuator/prometheus`, the meter taxonomy, and the `ProviderHealthDto` schema.
- [~] 6.2 Update `docs/ARCHITECTURE_TR.md` with a Turkish "İşlevsel Olmayan Gözlemlenebilirlik" subsection.
- [~] 6.3 Update `.kiro/steering/structure.md` "Where to put new code" table with: a new metric, a new admin endpoint, a new analytics sink, a new export format.

### 7. Renovate + Trivy CI — Requirements 6, 7

- [~] 7.1 Add `renovate.json` at repo root with `config:base`, Monday schedule, non-major grouping, Spring Boot major-pinned.
- [~] 7.2 Add `.github/workflows/ci.yml` with two jobs: `test` runs `mvnw -B verify -DskipITs`; `image-scan` builds the Docker image then runs `aquasecurity/trivy-action@<pinned-sha>` with `severity: HIGH,CRITICAL` and `exit-code: '1'`. Both jobs upload artifacts; `image-scan` uploads `trivy-report.sarif`.
- [~] 7.3 Document the workflow in `README.md` "Running Tests" section (mention SARIF artifact + how to interpret).

### 8. Smoke test + ship

- [~] 8.1 Local smoke test via `docker compose up --build`: scrape `/actuator/prometheus`, hit `/api/v1/admin/providers` with the admin token, POST to `/api/v1/admin/sync`, and download `/api/v1/search.csv?q=docker`.
- [~] 8.2 Push branch; open PR with checklist linking back to each requirement.
- [~] 8.3 Merge after CI passes (test job + Trivy job both green).

## Task Dependency Graph

```json
{
  "waves": [
    {
      "wave": 1,
      "tasks": ["1.1", "1.2", "1.3"]
    },
    {
      "wave": 2,
      "tasks": ["2.1", "2.2", "2.3", "2.4", "2.5", "3.1", "3.2", "3.3", "7.1", "7.2"]
    },
    {
      "wave": 3,
      "tasks": ["2.6", "2.7", "3.4", "3.5", "3.6", "3.7", "4.1", "5.1", "5.2", "7.3"]
    },
    {
      "wave": 4,
      "tasks": ["4.2", "4.3", "4.4", "4.5", "4.6", "4.7", "5.3", "5.4", "5.5", "5.6"]
    },
    {
      "wave": 5,
      "tasks": ["6.1", "6.2", "6.3"]
    },
    {
      "wave": 6,
      "tasks": ["8.1", "8.2", "8.3"]
    }
  ],
  "criticalPath": ["1.2", "2.1", "3.1", "4.1", "5.3", "8.1"]
}
```

Wave 1 sets up the branch and scaffold. Wave 2 lands all backend
infrastructure that other tasks depend on (metrics components, sync
coordinator, admin auth, CI files). Wave 3 starts wiring those into
controllers and adds the Flyway migration and the pure-Java CSV
utility. Wave 4 builds the analytics and export pipelines on top.
Wave 5 closes the documentation. Wave 6 is the ship gate.

## Notes

### Acceptance criteria mapping

| Requirement | Tasks |
| --- | --- |
| Req 1 (metrics)             | 2.1 — 2.7 |
| Req 2 (provider health)     | 3.2, 3.3, 3.4, 3.6 |
| Req 3 (manual sync)         | 3.1, 3.3, 3.5, 3.7 |
| Req 4 (search analytics)    | 4.1 — 4.7 |
| Req 5 (CSV / JSON export)   | 5.1 — 5.6 |
| Req 6 (Renovate)            | 7.1 |
| Req 7 (Trivy)               | 7.2, 7.3 |
| Non-functional 1 (compat)   | covered by existing IT suites; verified at 8.1 |
| Non-functional 2 (ArchUnit) | gated by every `mvnw verify` |
| Non-functional 3 (logs)     | 4.5, 4.6 |
| Non-functional 4 (security) | 3.3, 6.1 |
| Non-functional 5 (tests)    | one Test/PropertyTest/IT per section, listed above |
