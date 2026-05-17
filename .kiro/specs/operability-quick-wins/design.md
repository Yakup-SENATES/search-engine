# Design Document — Operability Quick Wins

## Overview

This feature adds five additive concerns to the existing service. Each is
self-contained and follows the existing clean-architecture rules:

- New cross-cutting collaborators (metrics, analytics) live in
  `infrastructure/<concern>/`.
- New HTTP endpoints live in `web/api/` with their DTOs co-located.
- A single new schema migration (`V2__search_analytics.sql`) is added; the
  existing `V1__init.sql` stays untouched.
- No domain or scoring change. ArchUnit stays green.

## Architecture

### 1. Metrics — Spring Boot Actuator + Micrometer + Prometheus

Spring Boot 3.4 already pulls in Micrometer. We only need the
`micrometer-registry-prometheus` runtime dependency. Spring auto-wires the
`MeterRegistry` and exposes `/actuator/prometheus` once we whitelist it in
`management.endpoints.web.exposure.include`.

Why Micrometer (not Prometheus client directly):

- Existing actuator infrastructure — adding the registry is a one-line
  POM change plus YAML.
- Micrometer Timers handle percentiles, histograms, and SLO buckets
  without manual aggregation.
- Vendor-portable: switching to Datadog or New Relic later is a registry
  swap, not a code rewrite.

Instrumentation strategy: small `@Component` collaborators
(`ProviderFetchMetrics`, `SearchMetrics`, `IngestMetrics`,
`RateLimitMetrics`) hold the meters and expose explicit `recordX(...)`
methods. The existing services call these explicitly — no AOP magic, no
hidden behavior. This keeps the meter names searchable from the call site.

### 2. Provider health — in-memory state via `ProviderHealthRegistry`

The orchestrator (`DefaultContentAggregator`) already iterates
`List<ContentProvider>` and tracks per-provider outcomes in logs. We
extract this into a `ProviderHealthRegistry` `@Component` that:

- Holds a `ConcurrentHashMap<String, ProviderHealthSnapshot>` keyed by
  provider name.
- Is updated by the aggregator on every fetch outcome (success or
  failure).
- Is read by the new `AdminProviderController` to render the JSON
  response.

The state is **process-local**. For multi-instance deployments, a
follow-up feature would back this with Redis, but for the operator-tooling
use case, per-instance visibility is sufficient (and faster).

### 3. Manual sync — reuse existing `AtomicBoolean` guard

`SyncScheduler` already uses an `AtomicBoolean running` to skip overlapping
runs. We extract that guard into a `SyncCoordinator` `@Component` that
both `SyncScheduler` and the new `AdminSyncController` share, so the
"already running" semantics are consistent across triggers. The actual
`runSync()` call is dispatched on a Spring `TaskExecutor` so the HTTP
request returns immediately with 202 Accepted.

### 4. Search analytics — pluggable sink behind one interface

`SearchAnalyticsSink` interface in `application/analytics/` (new package),
with three implementations under `infrastructure/analytics/`:

- `JdbcSearchAnalyticsSink` — INSERT into `search_analytics` table.
- `LogSearchAnalyticsSink` — single structured-log line per call.
- `NoOpSearchAnalyticsSink` — for `analytics.search.enabled=false`.

A small `SearchAnalyticsRecorder` collaborator wraps the sink in a
`try/catch` and degrades to a warning log on failure (best-effort).
Recording happens in the `SearchController` (after the result is
materialized) — not inside `DefaultSearchService` — so the cache hit/miss
flag and the resolved client IP are both available without polluting the
application service.

The IP is hashed with SHA-256 hex (no salt; stable across instances) so
operator queries like "top zero-result queries from one IP" stay
possible without storing PII.

### 5. CSV / JSON export — content-negotiation by URL suffix

Two new methods in a new `ExportController`:

- `GET /api/v1/search.csv` — streams CSV via `StreamingResponseBody` so
  the response is constant-memory regardless of `limit`.
- `GET /api/v1/search.json` — returns a Jackson-serialized list.

URL suffix is preferred over `Accept` header because:

- It works in `<a href="...">` links (no header manipulation needed).
- It survives copy-paste into a browser bar.
- It makes the CSV download an explicit user choice (the existing
  `/api/v1/search` keeps returning JSON unchanged).

CSV escaping is done by a small pure-Java `Csv` utility under
`web/api/csv/`, covered by both unit and jqwik property tests
(round-trip property: `parse(format(rows)) == rows`).

### 6. Renovate + Trivy — repository-level configs

Two new files at the repo root:

- `renovate.json` — extends `config:base`, schedules weekly Mondays,
  groups non-major bumps, blocks Spring Boot major automerge.
- `.github/workflows/ci.yml` — runs `mvnw -B verify -DskipITs`, then
  `aquasecurity/trivy-action` (pinned by SHA) against the built image,
  uploads SARIF artifact, fails on HIGH/CRITICAL.

No code change is needed in the application; these are pure CI/devops
files.

### 7. Admin auth — single static API token

We add a new `AdminAuthFilter` (`OncePerRequestFilter`) registered before
the rate-limit filter. It validates the `X-Admin-Token` header against
`ADMIN_API_TOKEN` for any URI starting with `/api/v1/admin/`. Mismatch →
HTTP 401 `UNAUTHORIZED` with the standardized error envelope. Constant-
time comparison via `MessageDigest.isEqual` to avoid timing attacks.

Why a static token (not Spring Security):

- The admin surface is operator-only and small (two endpoints).
- Spring Security would be a much bigger, opinionated dependency for
  the size of the surface.
- The token is supplied via env var, redacted by the existing
  `SecretRedactingPropertySource`, and easy to rotate.

A future iteration can replace this with Spring Security + JWT without
breaking the existing API.

## Components and Interfaces

### New source files

```
src/main/java/com/example/searchengine/
├── application/analytics/
│   ├── package-info.java
│   ├── SearchAnalyticsSink.java          (interface)
│   ├── SearchAnalyticsRecord.java        (record)
│   └── SearchAnalyticsRecorder.java      (best-effort wrapper)
│
├── infrastructure/
│   ├── analytics/
│   │   ├── package-info.java
│   │   ├── JdbcSearchAnalyticsSink.java
│   │   ├── LogSearchAnalyticsSink.java
│   │   ├── NoOpSearchAnalyticsSink.java
│   │   ├── AnalyticsConfig.java          (sink selection by property)
│   │   └── AnalyticsProperties.java      (@ConfigurationProperties)
│   │
│   ├── metrics/
│   │   ├── package-info.java
│   │   ├── MetricsConfig.java
│   │   ├── ProviderFetchMetrics.java
│   │   ├── SearchMetrics.java
│   │   ├── IngestMetrics.java
│   │   └── RateLimitMetrics.java
│   │
│   ├── admin/
│   │   ├── package-info.java
│   │   ├── AdminAuthFilter.java
│   │   ├── AdminAuthProperties.java
│   │   └── ProviderHealthRegistry.java   (in-memory ConcurrentHashMap)
│   │
│   └── sync/
│       └── SyncCoordinator.java          (extracted AtomicBoolean guard)
│
└── web/api/
    ├── AdminProviderController.java
    ├── ProviderHealthDto.java
    ├── AdminSyncController.java
    ├── ManualSyncResponse.java
    ├── ExportController.java
    ├── csv/
    │   ├── package-info.java
    │   └── Csv.java                      (pure-Java escaping/formatting)
    └── ...
```

### Modified source files

| File | Change |
| --- | --- |
| `pom.xml` | Add `micrometer-registry-prometheus` runtime dependency. |
| `application.yaml` | Add `management.endpoints.web.exposure.include`, `analytics.search.*`, `export.search.*`, `admin.auth.token`. |
| `application-local.yaml` | Mirror operator-friendly defaults (analytics sink=log, admin token disabled). |
| `DefaultContentAggregator.java` | Inject `IngestMetrics` + `ProviderHealthRegistry`; record outcomes. Use `SyncCoordinator` instead of local AtomicBoolean. |
| `SyncScheduler.java` | Delegate to `SyncCoordinator.tryRun(...)`. |
| `JsonProviderClient.java`, `XmlProviderClient.java` | Wrap `fetch()` calls with `ProviderFetchMetrics.record(...)`. |
| `DefaultSearchService.java` | Inject `SearchMetrics`; record query timer + cache hit/miss. |
| `RateLimitFilter.java` | Increment `RateLimitMetrics` on rejection. |
| `SearchController.java` | After search, post a `SearchAnalyticsRecord` to `SearchAnalyticsRecorder`. |
| `dashboard.html` | Add two "Download" links (CSV / JSON). |

## Data Models

### New SQL migration

```sql
-- src/main/resources/db/migration/V2__search_analytics.sql
CREATE TABLE search_analytics (
  id               BIGSERIAL PRIMARY KEY,
  requested_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  q                VARCHAR(200) NOT NULL,
  type             VARCHAR(16),
  sort             VARCHAR(16)  NOT NULL,
  page             INTEGER      NOT NULL,
  "limit"          INTEGER      NOT NULL,
  total_results    BIGINT,
  latency_ms       INTEGER      NOT NULL,
  cache_hit        BOOLEAN      NOT NULL,
  request_id       VARCHAR(64),
  client_ip_hash   CHAR(64),
  error_code       VARCHAR(32)
);

CREATE INDEX idx_search_analytics_requested_at ON search_analytics (requested_at DESC);
CREATE INDEX idx_search_analytics_q             ON search_analytics (q);
```

### New CI / repo files

```
renovate.json
.github/workflows/ci.yml
```

## Correctness Properties

### Property 1: No PII in metrics

**Validates: Requirements 1.8**

`/actuator/prometheus` MUST NOT expose any tag value matching the
patterns for the user-supplied `q` parameter, the `requestId` MDC value,
or an IPv4/IPv6 literal. Verified by `MetricsPiiPropertyTest`
(jqwik 200 iterations) which fires random search queries through the
controller and scrapes the Prometheus endpoint after each one.

### Property 2: CSV round-trip preserves payload

**Validates: Requirements 5.4**

For any list of `DashboardRow`-shaped string rows, parsing the CSV
produced by `Csv.format(rows)` MUST yield a list deeply equal to the
input. Verified by `CsvRoundTripPropertyTest` (jqwik 200 iterations)
generating arbitrary printable Unicode strings including comma, quote,
CR, and LF characters.

### Property 3: ClientIpHasher determinism

**Validates: Requirements 4.1**

For any IPv4 / IPv6 string `ip`, `ClientIpHasher.hash(ip)` MUST return
a 64-character hex string identical across calls within and across JVM
instances. Verified by `ClientIpHasherPropertyTest` (jqwik 200
iterations).

### Property 4: SyncCoordinator mutual exclusion

**Validates: Requirements 3.2**

For any concurrent invocation pattern, at most one `Runnable` passed to
`SyncCoordinator.tryRun(...)` is executing at any given instant. The
second concurrent caller MUST observe `isRunning() == true` and the
first MUST eventually flip the flag back to `false`. Verified by
`SyncCoordinatorConcurrencyTest` driving multiple threads against a
single coordinator instance.

### Sequence diagrams

### Search request with analytics + metrics

```
Client → SearchController.search()
              │
              ├─→ SearchService.search() ── Timer.start()
              │       │
              │       ├─→ Cache.get()   → hit/miss
              │       └─→ Repository.search() (on miss)
              │
              ├─→ Timer.stop() (SearchMetrics)
              ├─→ SearchAnalyticsRecorder.record(...) ── try/catch
              └─→ ResponseEntity(SearchResponse)
```

### Manual sync trigger

```
Operator → AdminSyncController.trigger()
                  │
                  ├─→ SyncCoordinator.tryStart()
                  │      ↳ if running == true   → return alreadyRunning=true
                  │      ↳ if running == false  → spawn TaskExecutor.execute(...)
                  │
                  └─→ HTTP 202 { "triggered": true|false, "alreadyRunning": ... }

(in background)
TaskExecutor → DefaultContentAggregator.runSync()
                  ↳ updates ProviderHealthRegistry per provider
                  ↳ on completion: SyncCoordinator.markFinished()
                  ↳ @CacheEvict(allEntries=true) fires
```

## Configuration surface

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,prometheus

analytics:
  search:
    enabled: ${ANALYTICS_ENABLED:true}
    sink:    ${ANALYTICS_SINK:db}     # db | log | none

export:
  search:
    max-rows: ${EXPORT_MAX_ROWS:1000}

admin:
  auth:
    token: ${ADMIN_API_TOKEN:}        # required for /api/v1/admin/* — empty disables admin
```

## Out of scope

- Multi-instance shared state for ProviderHealthRegistry (Redis-backed).
- Search analytics dashboards (Grafana etc.).
- Real authentication/authorization stack (Spring Security + JWT).
- API versioning of admin endpoints (we keep `/api/v1/admin/*` for now).
- Per-tenant rate limiting.

These are tracked as candidates for a follow-up "operability v2" spec.

## Error Handling

### Risk register

| Risk | Mitigation |
| --- | --- |
| Prometheus endpoint accidentally exposes PII | Lint test that asserts no metric tag value contains `q`, `requestId`, or raw IP. |
| Analytics persistence becomes a bottleneck | Sink runs best-effort (try/catch); DB writes are async via TaskExecutor. |
| CSV export OOMs on huge `limit` | Hard cap at `EXPORT_MAX_ROWS=1000`; streaming response body. |
| Trivy false-positives block CI | Allow operators to set `severity: HIGH,CRITICAL` and use `.trivyignore` for accepted findings. |
| Admin token leakage via logs | Existing `SecretRedactingPropertySource` already redacts `*_TOKEN` keys. Add explicit unit test. |


## Testing Strategy

The feature follows the project's existing three-tier test pyramid; each
new component lands with the appropriate suffix so it routes to the
right Maven plugin.

| Concern | Suffix | What it covers |
| --- | --- | --- |
| Pure-Java helpers (e.g. `Csv`, `ClientIpHasher`) | `*Test.java` + `*PropertyTest.java` | Unit assertions for known cases plus jqwik round-trip / determinism properties (≥ 100 iterations). |
| Spring-wired collaborators (metrics components, sinks, registry) | `*Test.java` | Mock-driven unit tests asserting meter names, tag keys, and side-effects. |
| Admin / export controllers, analytics persistence | `*IT.java` | Failsafe Testcontainers integration tests booting Spring + Postgres; assert HTTP envelope, auth filter, Flyway migration, and end-to-end recording. |
| PII guard for metrics | `*PropertyTest.java` | jqwik 200-iteration test exercising random search queries through the controller and scraping `/actuator/prometheus` to assert no tag value matches `q`, `requestId`, or an IPv4/IPv6 literal. |
| Backwards compatibility | existing `*IT.java` | The pre-existing `SearchApiIT`, `DashboardControllerIT`, `SyncFlowIT` suites stay green — any regression is a build failure. |

Coverage gates:

- **Every requirement** in `requirements.md` MUST map to at least one
  failing-then-passing test created in the same task that introduces it
  (test-first or test-with).
- **ArchUnit** stays green throughout: domain framework-free, scoring
  pure, web does not import infrastructure.
- **Mutation testing** is out of scope for this feature but the new
  `Csv` utility and `ClientIpHasher` MUST have ≥ 90% line coverage to
  make a follow-up PIT run easy.
