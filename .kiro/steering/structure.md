# Project Structure

## Root layout

```
searchengine/
├── pom.xml                        Spring Boot 3.4 / Java 21 build, Surefire + Failsafe wiring
├── mvnw, mvnw.cmd, .mvn/          Maven wrapper
├── Dockerfile                     Multi-stage build → JRE 21 image with /actuator/health HEALTHCHECK
├── docker-compose.yml             app + postgres:16 + redis:7-alpine
├── README.md, HELP.md             Setup, env vars, troubleshooting
├── instructions.md                Original project blueprint (case study spec)
├── project_information.md         Short Turkish/English overview
├── src/                           Application + tests (see below)
└── .kiro/
    ├── specs/                     Spec-driven workflow artifacts (requirements/design/tasks)
    └── steering/                  These guidance docs
```

## Java source — Clean Architecture, four concentric layers

Root package: `com.example.searchengine`. Inward dependency rule is enforced by ArchUnit (`src/test/java/.../architecture/`).

```
com.example.searchengine
├── SearchengineApplication.java   Single @SpringBootApplication entry point
│
├── domain/                        Pure Java — NO framework imports
│   ├── content/                   Content aggregate, ContentRepository port, SearchCriteria,
│   │                              SearchPage, SortField, ContentType, UpsertOutcome,
│   │                              ContentRepositoryException
│   ├── provider/                  ContentProvider port, RawContent, ProviderFetchResult
│   └── scoring/                   ScoringEngine + DefaultScoringEngine + 4 pure calculators
│                                  (BaseScore, TypeMultiplier, Engagement, Freshness),
│                                  ScoreBreakdown
│
├── application/                   Use-cases / orchestration; may import domain only
│   ├── ingest/                    Normalizer + DefaultNormalizer, ContentAggregator +
│   │                              DefaultContentAggregator, NormalizationResult, IngestConfig
│   ├── search/                    SearchService + DefaultSearchService, SearchQuery, SearchResult
│   └── scheduler/                 SyncScheduler (@Scheduled wrapper around ContentAggregator)
│
├── infrastructure/                Adapters / framework wiring; may import application + domain
│   ├── persistence/               ContentEntity (JPA), ContentJpaRepository,
│   │                              ContentRepositoryAdapter
│   ├── provider/                  HttpClientConfig, plus one package per provider:
│   │   ├── jsonprovider/            client + DTO(s) + mapper + adapter (RestClient + Jackson)
│   │   └── xmlprovider/             client + DTO(s) + mapper + adapter (RestClient + JAXB)
│   ├── cache/                     CacheConfig, CacheProperties, ResilientCacheManager,
│   │                              SearchCacheKeyGenerator
│   ├── ratelimit/                 RateLimitFilter, LoggingOnlyRateLimitFilter, ClientIpResolver,
│   │                              RateLimitConfig + RateLimitProperties
│   ├── logging/                   RequestIdFilter, RequestIdFilterConfig
│   └── config/                    @ConfigurationProperties + ConfigValidator + ClockConfig +
│                                  SchedulingConfig + Secret redaction (logging guard)
│
└── web/                           HTTP layer — controllers + DTOs ONLY; may NOT import infrastructure
    ├── api/                       SearchController, SearchRequest, SearchResponse,
    │                              ContentSummaryDto, PaginationDto
    ├── dashboard/                 DashboardController, DashboardRow, IgnoredParamNotice
    ├── error/                     GlobalExceptionHandler, ErrorResponse, ErrorMessageSanitizer,
    │                              ProviderException, RequestSizeLimitFilter
    └── openapi/                   OpenApiConfig
```

### Layer rules (enforced by ArchUnit)

- `domain` imports only the JDK and other `domain` packages. No `org.springframework.*`, `jakarta.persistence.*`, `com.fasterxml.jackson.*`, `jakarta.xml.*`.
- `application` imports `domain` only.
- `infrastructure` and `web` may import `application` and `domain`.
- `web` may **NOT** import `infrastructure`. Controllers go through application services, never directly to JPA / Redis / providers.
- `domain.scoring` has the strictest rule: framework-free **and** deterministic — accept the evaluation `Instant` as a parameter, never call `Instant.now()` inside.

## Resources

```
src/main/resources/
├── application.yaml              Default config — every value bound to an env var
├── application-local.yaml        Developer profile (localhost defaults, rate limit off)
├── logback-spring.xml            JSON structured logging (logstash-logback-encoder)
├── db/migration/
│   └── V1__init.sql              Flyway baseline: contents table, indexes, GIN tsvector
└── templates/
    └── dashboard.html            Thymeleaf dashboard view
```

## Test layout — mirrors main, plus an `architecture/` package

```
src/test/java/com/example/searchengine
├── SearchengineApplicationTests.java
├── architecture/                 ArchUnit rules (CleanArchitecture, DomainPurity, repo location)
├── domain/                       Unit + property tests for Content, ContentType, scoring
├── application/                  Unit + property tests for ingest + search; SyncFlowIT
├── infrastructure/               Unit + property + IT for cache, config, logging, persistence,
│                                 provider (JSON + XML), ratelimit
└── web/                          Unit + IT for api, dashboard, error, openapi
```

Test-class suffix decides where it runs:

- `*Test.java` → Surefire (unit + ArchUnit)
- `*PropertyTest.java` → Surefire (jqwik, ≥ 100 iterations)
- `*IT.java` → Failsafe (Testcontainers — needs Docker)

## Where to put new code

| Adding… | Goes in | Notes |
| --- | --- | --- |
| A new provider | `infrastructure/provider/<name>/` | Mirror `jsonprovider/` layout: client, DTOs, mapper, adapter; register adapter as `@Component`. |
| A new scoring component | `domain/scoring/` | Pure Java; no framework imports; cover with both unit and jqwik property tests. |
| A new search filter | Extend `SearchCriteria` (domain) → `SearchService` (application) → `SearchController` + DTO (web). |
| A new operator knob | `@ConfigurationProperties` in `infrastructure/config/`, default in `application.yaml`, document in README env-var table. |
| A new HTTP endpoint | New `@RestController` in `web/api/` + DTOs; call into an application service, never into infrastructure directly. |
| A schema change | New Flyway migration `Vn__description.sql` in `src/main/resources/db/migration/` — never modify `V1__init.sql`. |
| A new cross-cutting filter | `infrastructure/<concern>/` (logging, ratelimit, …); register via a Spring `@Configuration`. |
| A new metric | `infrastructure/metrics/` | Create a `@Component` with Micrometer meters; inject into the relevant service. Add unit test in `src/test/java/.../infrastructure/metrics/`. |
| A new admin endpoint | `web/api/` | Create a `@RestController` under `/api/v1/admin/`; protected by `AdminAuthFilter`. Add unit test + IT. |
| A new analytics sink | `infrastructure/analytics/` | Implement `SearchAnalyticsSink` interface; register in `AnalyticsConfig` via a new `analytics.search.sink` value. |
| A new export format | `web/api/` | Add a new method to `ExportController` (or a new controller); reuse `SearchService.search(SearchQuery)`. |
