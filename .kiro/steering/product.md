# Product

**Search Engine Aggregator Service** — a Java 21 / Spring Boot 3.4 backend that ingests content from heterogeneous external providers (one JSON, one XML), normalizes it into a unified `Content` aggregate, computes a deterministic ranking score in a framework-free domain component, persists results in PostgreSQL with full-text search support, and exposes both a paginated REST search API and a server-rendered Thymeleaf dashboard.

## Core capabilities

- **Aggregation** — periodic scheduled sync (default every 5 min) pulls from all registered `ContentProvider` implementations; failures in one provider do not affect others.
- **Normalization** — provider-specific DTOs are mapped into the canonical `Content` aggregate (e.g., `article` → `text`, `(provider, external_id)` uniqueness).
- **Ranking** — pure-Java Scoring Engine: `finalScore = (baseScore × typeMultiplier) + freshnessScore + engagementScore`. Deterministic, no framework imports, evaluation timestamp passed explicitly.
- **Search API** — `GET /api/v1/search?q=&type=&sort=&page=&limit=` with full-text search via PostgreSQL `tsvector` + GIN index, deterministic ordering by `final_score DESC, id ASC`.
- **Dashboard** — `GET /dashboard` renders Title / Type / Score in Thymeleaf.
- **Cross-cutting concerns** — Redis-backed search cache (evicted after each sync), Bucket4j rate limiting (100 req/60 s/IP, Redis-backed), Resilience4j retry + time limiter on provider calls, structured JSON logs with `requestId` MDC propagation, global exception handler with standardized error envelopes.

## Design principles

- **Clean Architecture** with strict inward dependencies; enforced at build time by ArchUnit.
- **Strategy Pattern** for providers — adding a new provider means dropping a new package under `infrastructure/provider/`, no orchestrator changes.
- **Property-Based Testing** (jqwik) backs correctness properties for scoring, normalization, search invariants, and rate-limit transitions.
- **Operator-tunable via env vars** — every runtime knob is exposed; required secrets fail fast at startup; credentials redacted from logs.

## Endpoints

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/api/v1/search` | Keyword search with filtering, sorting, pagination |
| `GET` | `/dashboard` | Thymeleaf dashboard |
| `GET` | `/swagger-ui.html`, `/v3/api-docs` | OpenAPI documentation |
| `GET` | `/actuator/health` | Liveness probe used by Docker `HEALTHCHECK` |
