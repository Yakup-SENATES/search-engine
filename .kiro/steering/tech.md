# Tech Stack

## Runtime

- **Language**: Java 21 (LTS)
- **Framework**: Spring Boot 3.4.1 (`spring-boot-starter-parent`)
- **Build**: Maven via the bundled wrapper (`./mvnw`, `mvnw.cmd`); no host Maven required
- **Container**: multi-stage Dockerfile (Maven 3.9 + Eclipse Temurin 21 → `eclipse-temurin:21-jre`)
- **Compose stack**: `app` + `postgres:16` + `redis:7-alpine`, with healthcheck-gated startup

## Spring starters in use

`web`, `data-jpa`, `data-redis`, `cache`, `thymeleaf`, `validation`, `actuator`.

## Libraries

| Concern | Library |
| --- | --- |
| Database | PostgreSQL 16 + Flyway (`flyway-core`, `flyway-database-postgresql`) |
| API docs | `springdoc-openapi-starter-webmvc-ui` 2.7.0 |
| Resilience | Resilience4j 2.2.0 (`resilience4j-spring-boot3`) — `TimeLimiter` + `Retry` per provider |
| Rate limiting | Bucket4j 8.12.1 (`bucket4j_jdk17-core` + `bucket4j_jdk17-lettuce`) — Redis-backed buckets |
| XML | `jakarta.xml.bind-api` + `jaxb-runtime` |
| Logging | `logstash-logback-encoder` 8.0 — JSON structured logs via `logback-spring.xml` |
| Boilerplate | Lombok (annotation-processor only; excluded from the executable jar) |

## Test stack

| Suffix | Plugin | Contents |
| --- | --- | --- |
| `*Test.java` | Surefire | JUnit 5 unit tests, ArchUnit rules |
| `*PropertyTest.java` | Surefire | jqwik 1.9.2 property-based tests (≥ 100 iterations per property) |
| `*IT.java` | Failsafe | Testcontainers 1.20.4 integration tests (boot Spring + `postgres:16` container) |

Other test libs: `spring-boot-starter-test`, `archunit-junit5` 1.3.0, `wiremock-standalone` 3.10.0.

## Common commands

> Use `mvnw.cmd` on Windows (cmd shell) and `./mvnw` on macOS / Linux. The cmd separator is `&` (do **not** use `&&`).

```cmd
:: Build the executable jar (skip tests)
mvnw.cmd -B -DskipTests package

:: Run unit + property + ArchUnit tests only
mvnw.cmd -B test

:: Full verify — runs Surefire + Failsafe (Testcontainers needs Docker)
mvnw.cmd -B verify

:: Skip integration tests when Docker is unavailable
mvnw.cmd -B verify -DskipITs

:: Run the app locally with developer-friendly defaults
:: (localhost postgres/redis, faster sync, rate limit off)
set SPRING_PROFILES_ACTIVE=local
mvnw.cmd spring-boot:run
```

```cmd
:: Full Docker stack (requires a .env with secrets — see README)
docker compose up --build
docker compose logs -f app
docker compose down            :: keep the postgres_data volume
docker compose down -v         :: also wipe the volume
```

## Configuration

- **Default config**: `src/main/resources/application.yaml` — every operator-tunable value is bound to an env var.
- **Local profile**: `src/main/resources/application-local.yaml` — activated via `SPRING_PROFILES_ACTIVE=local`; safe localhost defaults.
- **Required secrets** (no default; startup fails fast if missing): `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `PROVIDER_JSON_URL`, `PROVIDER_XML_URL`. Never commit real values; use a gitignored `.env`.
- **Schema ownership**: Flyway owns schema migrations; `spring.jpa.hibernate.ddl-auto=validate` — never let Hibernate auto-migrate.
- **Migrations location**: `src/main/resources/db/migration/V1__init.sql` (single baseline so far).

## Conventions for code changes

- Domain layer (`domain/**`) must remain framework-free: no imports from Spring, JPA, Jackson, JAXB. ArchUnit enforces this — a misplaced import fails the build.
- The Scoring Engine (`domain/scoring`) is especially sensitive: keep it pure, accept the evaluation `Instant` as an explicit parameter, never call `Instant.now()` inside.
- Add new providers as a new package under `infrastructure/provider/<name>/` containing `client + DTO + mapper + adapter`; register the adapter as a Spring `@Component` and inject `List<ContentProvider>` in the orchestrator.
- Surface new operator knobs via `@ConfigurationProperties` in `infrastructure/config/`, default in `application.yaml`, document in the README env-var table.
- Use `plainto_tsquery` (or already-escaped equivalents) for any user-supplied search input — never concatenate raw input into SQL.
- Test-class naming matters: `*Test` / `*PropertyTest` / `*IT` route to the correct Maven plugin.
