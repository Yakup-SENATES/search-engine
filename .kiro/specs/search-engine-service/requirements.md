# Requirements Document

## Introduction

The Search Engine Aggregator Service is a backend platform that ingests content from heterogeneous external providers (one JSON-based and one XML-based), normalizes the data into a unified domain model, computes a deterministic ranking score, persists the records in PostgreSQL, and exposes a searchable REST API together with a server-rendered dashboard. The system is implemented with Java 21 and Spring Boot 3.4+ following Clean Architecture (domain, application, infrastructure, web layers). The Scoring Engine is a pure-Java domain component free of framework dependencies. Provider integrations follow the Strategy Pattern so additional providers can be added without modifying existing logic. Caching, rate limiting, scheduled synchronization, OpenAPI documentation, global error handling, and automated tests are first-class concerns.

## Glossary

- **Search_Engine_Service**: The complete backend application being built, including the API, scheduler, persistence, and dashboard.
- **Content_Aggregator**: The application-layer component that orchestrates fetching, normalizing, scoring, and persisting content from all configured providers.
- **Content_Provider**: A logical external source of content. The system supports two concrete provider types: `JSON_Provider` and `XML_Provider`.
- **Provider_Adapter**: A Strategy Pattern implementation of the `ContentProvider` interface that fetches and parses data from a single Content_Provider. Concrete implementations are `JSON_Provider_Adapter` and `XML_Provider_Adapter`.
- **JSON_Provider_Adapter**: The Provider_Adapter that retrieves and parses Provider 1 payloads in JSON format.
- **XML_Provider_Adapter**: The Provider_Adapter that retrieves and parses Provider 2 payloads in XML format.
- **Normalizer**: The component that converts a raw provider payload into the unified `Content` domain entity.
- **Content**: The unified internal domain entity persisted in PostgreSQL. Fields include `id`, `provider`, `external_id`, `title`, `description`, `type`, `views`, `likes`, `reading_time`, `reactions`, `duration`, `tags`, `published_at`, `final_score`, `popularity_score`, `relevance_score`, `created_at`, `updated_at`.
- **Content_Type**: An enumeration with values `video` and `text`. Provider-specific value `article` is mapped to `text`.
- **Scoring_Engine**: The pure-Java domain service that computes `final_score`, `popularity_score`, and `engagement_score` for a Content instance. Contains no framework dependencies.
- **Content_Repository**: The persistence-layer abstraction (interface in domain, implementation in infrastructure) for storing and querying Content entities in PostgreSQL.
- **Search_API**: The HTTP endpoint `GET /api/v1/search` that returns paginated, filtered, sorted Content results.
- **Search_Service**: The application-layer component that executes search queries against the Content_Repository on behalf of the Search_API.
- **Dashboard_UI**: The server-rendered web interface served at `GET /dashboard` listing Content with sorting and filtering.
- **Cache_Manager**: The Spring Cache abstraction (Redis-backed in production) used to cache search responses.
- **Rate_Limiter**: The component that enforces per-client request quotas on the public HTTP API.
- **Sync_Scheduler**: The Spring scheduled component that periodically triggers Content_Aggregator to refresh data from all Provider_Adapters.
- **Exception_Handler**: The Spring `@ControllerAdvice` global exception handler that converts exceptions into a standardized error response.
- **OpenAPI_Module**: The SpringDoc-OpenAPI configuration that exposes interactive API documentation at `/swagger-ui.html`.
- **Logger**: The structured logging facade (SLF4J) used by all components.
- **Freshness_Score**: A numeric bonus added to `final_score` based on the age of the Content as of the calculation time.
- **Engagement_Score**: A numeric component of `final_score` derived from interaction metrics relative to consumption metrics.
- **Round_Trip_Property**: A test property asserting that parsing a serialized payload then re-serializing produces an equivalent payload.

## Requirements

### Requirement 1: Provider Abstraction and Extensibility

**User Story:** As a backend architect, I want a uniform provider abstraction, so that new content sources can be integrated without modifying existing provider code.

#### Acceptance Criteria

1. THE Search_Engine_Service SHALL expose a `ContentProvider` interface in the domain layer that declares a `fetch()` operation returning a list of raw provider payloads and a `name()` operation returning a unique provider identifier.
2. THE Search_Engine_Service SHALL provide at least two concrete Provider_Adapter implementations: JSON_Provider_Adapter and XML_Provider_Adapter.
3. WHERE a new Content_Provider is configured, THE Content_Aggregator SHALL discover the corresponding Provider_Adapter via Spring dependency injection without requiring changes to the Content_Aggregator class.
4. THE Search_Engine_Service SHALL register each Provider_Adapter as an independent Spring bean so that adapters can be enabled or disabled through configuration.
5. IF two Provider_Adapter beans return the same value from `name()`, THEN THE Search_Engine_Service SHALL fail application startup with a descriptive error message.
6. IF a Content_Provider is referenced in configuration but no matching Provider_Adapter bean is discovered at startup, THEN THE Search_Engine_Service SHALL complete application startup, SHALL log a warning that names the missing adapter, and SHALL omit that Content_Provider from subsequent sync runs.

### Requirement 2: JSON Provider Integration

**User Story:** As a content engineer, I want the system to fetch content from the JSON provider, so that JSON-formatted videos appear in search results.

#### Acceptance Criteria

1. WHEN the Content_Aggregator invokes JSON_Provider_Adapter, THE JSON_Provider_Adapter SHALL issue an HTTP GET request to the configured JSON provider endpoint with a connection timeout of 5 seconds and a read timeout of 10 seconds.
2. WHEN the JSON provider returns an HTTP status in the range 200-299 with a JSON payload conforming to the documented schema (`contents[]` with `id`, `title`, `type`, `metrics.views`, `metrics.likes`, `metrics.duration`, `published_at`, `tags`), THE JSON_Provider_Adapter SHALL parse up to 1000 content items from the payload into a list of provider DTOs.
3. WHEN the JSON_Provider_Adapter parses a content item, THE JSON_Provider_Adapter SHALL map `id` to `external_id`, `title` to `title`, `type` to `type`, `metrics.views` to `views`, `metrics.likes` to `likes`, `metrics.duration` to `duration`, `published_at` to `published_at`, and `tags` to `tags`.
4. IF the JSON provider returns an HTTP status outside the range 200-299, THEN THE JSON_Provider_Adapter SHALL log the status code and request URL and SHALL return an empty list without propagating an exception to the Content_Aggregator.
5. IF the JSON provider response cannot be deserialized as the expected schema or a content item is missing any required field (`id`, `title`, `type`, `metrics.views`, `metrics.likes`, `metrics.duration`, `published_at`), THEN THE JSON_Provider_Adapter SHALL log the parse failure with the offending field path and item identifier when available and SHALL skip the unparsable item while continuing to process valid items.
6. IF the HTTP request to the JSON provider fails due to a connection error, a timeout, or any network-level failure preventing receipt of a response, THEN THE JSON_Provider_Adapter SHALL log the failure cause and request URL and SHALL return an empty list without propagating an exception to the Content_Aggregator.

### Requirement 3: XML Provider Integration

**User Story:** As a content engineer, I want the system to fetch content from the XML provider, so that articles and videos from that source appear in search results.

#### Acceptance Criteria

1. WHEN the Content_Aggregator invokes XML_Provider_Adapter, THE XML_Provider_Adapter SHALL issue an HTTP GET request to the configured XML provider endpoint and parse the response body as XML using the documented `<feed><items><item/></items></feed>` schema.
2. WHEN the XML_Provider_Adapter parses a content item, THE XML_Provider_Adapter SHALL map `id` to `external_id`, `headline` to `title`, `type` to `type`, `stats.views` to `views`, `stats.likes` to `likes`, `stats.reading_time` to `reading_time`, `stats.reactions` to `reactions`, `publication_date` to `published_at`, and each `categories.category` element to a `tags` entry.
3. WHEN the XML_Provider_Adapter encounters a content item with `type` equal to `article`, THE XML_Provider_Adapter SHALL set the normalized Content_Type to `text`.
4. WHEN the XML_Provider_Adapter encounters a content item with `type` equal to `video`, THE XML_Provider_Adapter SHALL set the normalized Content_Type to `video`.
5. IF an XML element listed as optional (such as `stats.comments` or `categories`) is absent, THEN THE XML_Provider_Adapter SHALL use the configured default value (zero for numeric metrics, empty list for tags) and SHALL continue parsing the item.
6. IF the XML payload is malformed and cannot be parsed, THEN THE XML_Provider_Adapter SHALL log the parse error and SHALL return an empty list without raising an exception that aborts other providers, regardless of the suspected cause of the malformation.

### Requirement 4: Content Normalization

**User Story:** As a developer, I want raw provider payloads converted into a unified domain model, so that downstream components operate on a single consistent schema.

#### Acceptance Criteria

1. THE Normalizer SHALL transform every raw provider payload into a Content entity that contains the fields `provider`, `external_id`, `title`, `type`, `views`, `likes`, `reading_time`, `reactions`, `duration`, `tags`, and `published_at`.
2. WHEN the Normalizer processes a payload, THE Normalizer SHALL set `provider` to the value returned by the originating Provider_Adapter `name()` operation.
3. IF a normalized Content has a missing or empty `title`, an unrecognized `type`, an invalid `published_at`, or a missing `external_id`, THEN THE Normalizer SHALL reject the item, log the validation failure with the offending field name, and SHALL exclude the item from persistence.
4. IF a normalized Content has a numeric metric (`views`, `likes`, `reactions`, `reading_time`) that is negative, THEN THE Normalizer SHALL reject the item and SHALL log the validation failure.
5. THE Normalizer SHALL accept only `video` or `text` as valid Content_Type values after provider-specific mapping.

### Requirement 5: Content Persistence and Duplicate Prevention

**User Story:** As an operator, I want normalized content stored in PostgreSQL with duplicate prevention, so that repeated provider fetches do not create duplicate records.

#### Acceptance Criteria

1. THE Content_Repository SHALL persist Content entities in a PostgreSQL `contents` table with a UNIQUE constraint on the composite key (`provider`, `external_id`).
2. WHEN the Content_Aggregator persists a Content whose (`provider`, `external_id`) pair already exists, THE Content_Repository SHALL update the existing row's `title`, `description`, `type`, `views`, `likes`, `reading_time`, `reactions`, `duration`, `tags`, `published_at`, `final_score`, `popularity_score`, `relevance_score`, and `updated_at` fields rather than inserting a new row.
3. WHEN a new Content is inserted, THE Content_Repository SHALL set `created_at` and `updated_at` to the current timestamp.
4. THE Content_Repository SHALL create database indexes on `type` and on `final_score DESC`.
5. THE Content_Repository SHALL create a GIN index on `to_tsvector('simple', title || ' ' || coalesce(description, ''))` to support full-text keyword search.
6. IF a database write fails, THEN THE Content_Repository SHALL surface a domain-level repository exception that the Content_Aggregator logs without aborting processing of other Content items in the same sync batch.
7. WHEN a database write succeeds, THE Content_Aggregator SHALL log an info-level entry containing the `provider`, `external_id`, and the operation (`insert` or `update`) and SHALL continue processing the next Content item.

### Requirement 6: Scoring Engine Formula

**User Story:** As a product owner, I want a deterministic scoring algorithm in the domain layer, so that ranking is consistent, testable, and free of framework coupling.

#### Acceptance Criteria

1. THE Scoring_Engine SHALL be implemented as a pure-Java component in the domain layer with no Spring, JPA, Jackson, or other framework dependencies on its public API or implementation.
2. WHEN the Scoring_Engine receives a Content with `type` equal to `video`, THE Scoring_Engine SHALL compute Base_Score as `(views / 1000.0) + (likes / 100.0)`.
3. WHEN the Scoring_Engine receives a Content with `type` equal to `text`, THE Scoring_Engine SHALL compute Base_Score as `reading_time + (reactions / 50.0)`.
4. WHEN the Scoring_Engine receives a Content with `type` equal to `video`, THE Scoring_Engine SHALL apply a Type_Multiplier of `1.5`.
5. WHEN the Scoring_Engine receives a Content with `type` equal to `text`, THE Scoring_Engine SHALL apply a Type_Multiplier of `1.0`.
6. WHEN the Scoring_Engine receives a Content with `type` equal to `video` and `views` greater than zero, THE Scoring_Engine SHALL compute Engagement_Score as `(likes / views) * 10`.
7. WHEN the Scoring_Engine receives a Content with `type` equal to `text` and `reading_time` greater than zero, THE Scoring_Engine SHALL compute Engagement_Score as `(reactions / reading_time) * 5`.
8. IF the denominator of the Engagement_Score expression for a Content is zero, THEN THE Scoring_Engine SHALL set Engagement_Score to `0`.
9. THE Scoring_Engine SHALL compute Final_Score as `(Base_Score * Type_Multiplier) + Freshness_Score + Engagement_Score`.
10. WHEN the Scoring_Engine is invoked twice with the same Content snapshot and the same evaluation timestamp, THE Scoring_Engine SHALL return identical numeric Final_Score values.
11. THE Scoring_Engine SHALL recompute Freshness_Score from the supplied evaluation timestamp on every invocation and SHALL not cache or persist Freshness_Score as part of the Content snapshot.

### Requirement 7: Freshness Scoring

**User Story:** As a product owner, I want recent content to rank higher, so that users see fresh material first.

#### Acceptance Criteria

1. WHEN the Scoring_Engine evaluates a Content whose `published_at` is within 7 days of the evaluation timestamp, THE Scoring_Engine SHALL set Freshness_Score to `5`.
2. WHEN the Scoring_Engine evaluates a Content whose `published_at` is older than 7 days and within 30 days of the evaluation timestamp, THE Scoring_Engine SHALL set Freshness_Score to `3`.
3. WHEN the Scoring_Engine evaluates a Content whose `published_at` is older than 30 days and within 90 days of the evaluation timestamp, THE Scoring_Engine SHALL set Freshness_Score to `1`.
4. WHEN the Scoring_Engine evaluates a Content whose `published_at` is older than 90 days from the evaluation timestamp, THE Scoring_Engine SHALL set Freshness_Score to `0`.
5. THE Scoring_Engine SHALL accept the evaluation timestamp as an explicit parameter so that scoring is deterministic and unit-testable without relying on `System.currentTimeMillis()`.

### Requirement 8: Search API Keyword Query

**User Story:** As an API consumer, I want to search content by keyword, so that I can retrieve items relevant to a search term.

#### Acceptance Criteria

1. THE Search_API SHALL expose the endpoint `GET /api/v1/search` accepting a required query parameter `q` of type string.
2. WHEN the Search_API receives a request with a `q` value of length between 1 and 200 characters inclusive, THE Search_Service SHALL execute a PostgreSQL full-text search against the `title` and `description` fields and return matching Content records.
3. IF the Search_API receives a request without `q` or with a `q` value of length zero, THEN THE Search_API SHALL respond with HTTP 400 and an error body containing `code` set to `INVALID_QUERY` and a human-readable message.
4. IF the Search_API receives a `q` value longer than 200 characters, THEN THE Search_API SHALL respond with HTTP 400 and an error body containing `code` set to `INVALID_QUERY`.
5. WHEN the Search_API processes a `q` value, THE Search_Service SHALL escape PostgreSQL full-text query metacharacters before executing the query.

### Requirement 9: Search API Filtering, Sorting, and Pagination

**User Story:** As an API consumer, I want to filter, sort, and paginate search results, so that I can navigate large result sets efficiently.

#### Acceptance Criteria

1. WHERE the request includes the `type` query parameter with the value `video` or `text`, THE Search_Service SHALL restrict results to Content whose `type` matches the parameter.
2. IF the `type` query parameter is present with a value other than `video` or `text`, THEN THE Search_API SHALL respond with HTTP 400 and an error body containing `code` set to `INVALID_QUERY`.
3. WHERE the request includes the `sort` query parameter with the value `score`, THE Search_Service SHALL order results by `final_score` descending.
4. WHERE the request includes the `sort` query parameter with the value `popularity`, THE Search_Service SHALL order results by `popularity_score` descending.
5. WHERE the request includes the `sort` query parameter with the value `relevance`, THE Search_Service SHALL order results by full-text search rank descending.
6. WHERE the `sort` query parameter is absent, THE Search_Service SHALL order results by `final_score` descending.
7. WHERE the request includes the `page` query parameter, THE Search_Service SHALL accept integer values greater than or equal to 1 and SHALL use 1 as the default when the parameter is absent.
8. WHERE the request includes the `limit` query parameter, THE Search_Service SHALL accept integer values between 1 and 100 inclusive and SHALL use 10 as the default when the parameter is absent.
9. IF the `page` or `limit` parameter is present with a non-integer value or a value outside the accepted range, THEN THE Search_API SHALL respond with HTTP 400 and an error body containing `code` set to `INVALID_QUERY`.
10. THE Search_API SHALL return a JSON response body containing a `data` array of Content summaries (`id`, `title`, `type`, `score`) and a `pagination` object with `page`, `limit`, and `total` fields.
11. WHEN no Content matches the search criteria, THE Search_API SHALL respond with HTTP 200, an empty `data` array, and a `pagination.total` value of 0.

### Requirement 10: Dashboard UI

**User Story:** As an internal user, I want a web dashboard listing aggregated content, so that I can browse indexed items without using the API.

#### Acceptance Criteria

1. WHEN a GET request is received at `/dashboard`, THE Search_Engine_Service SHALL respond with a server-rendered Thymeleaf HTML page within 2 seconds for result sets of up to 1000 Content rows.
2. THE Dashboard_UI SHALL display a table containing exactly three columns with headers `Title`, `Type`, and `Score`, where each row corresponds to one Content item.
3. WHEN the Dashboard_UI is loaded without `sort` or `type` query parameters, THE Dashboard_UI SHALL display up to 20 Content rows ordered by `final_score` descending, with ties broken by Content `id` ascending.
4. IF the Dashboard_UI request includes a `sort` parameter with the value `score`, `popularity`, or `relevance`, THEN THE Dashboard_UI SHALL order rows according to the same rules defined in Requirement 9 (clauses 3-5).
5. IF the Dashboard_UI request includes a `sort` parameter with a value other than `score`, `popularity`, or `relevance`, THEN THE Dashboard_UI SHALL render the page using the default ordering by `final_score` descending and display a visible indication that the provided sort value was ignored.
6. IF the Dashboard_UI request includes a `type` parameter with the value `video` or `text`, THEN THE Dashboard_UI SHALL restrict rows to Content whose type field exactly matches the parameter value.
7. IF the Dashboard_UI request includes a `type` parameter with a value other than `video` or `text`, THEN THE Dashboard_UI SHALL render the page without applying any type filter and display a visible indication that the provided type value was ignored.
8. IF no Content rows match the applied filters, THEN THE Dashboard_UI SHALL render the table headers and display a visible message indicating that no Content items are available.

### Requirement 11: Background Synchronization

**User Story:** As an operator, I want providers polled on a schedule, so that the search index stays up to date without manual intervention.

#### Acceptance Criteria

1. THE Sync_Scheduler SHALL invoke the Content_Aggregator at a fixed interval configurable through the application property `aggregator.sync.fixed-delay-ms`, with a default value of 300000 milliseconds (5 minutes).
2. WHEN the Sync_Scheduler triggers a sync run, THE Content_Aggregator SHALL fetch from every registered Provider_Adapter, normalize results, compute scores via the Scoring_Engine, and upsert Content records via the Content_Repository in that order.
3. IF one Provider_Adapter throws an exception during a sync run, THEN THE Content_Aggregator SHALL log the error with the provider name and SHALL continue invoking the remaining Provider_Adapters.
4. WHEN a sync run completes, THE Content_Aggregator SHALL invalidate cached search responses by clearing the `search` cache region.
5. WHERE the application property `aggregator.sync.enabled` is set to `false`, THE Sync_Scheduler SHALL omit scheduled invocations of the Content_Aggregator.

### Requirement 12: Caching of Search Responses

**User Story:** As an API consumer, I want repeated identical searches to be served from cache, so that response latency is reduced under load.

#### Acceptance Criteria

1. WHEN the Search_Service returns a successful response for a search request, THE Cache_Manager SHALL store the response in the cache region named `search` keyed by the tuple (`q`, `type`, `sort`, `page`, `limit`).
2. THE Cache_Manager SHALL apply a default time-to-live of 300 seconds to entries in the `search` cache region, configurable through the application property `cache.search.ttl-seconds` accepting integer values from 1 to 86400 inclusive.
3. WHEN a search request arrives whose key matches a non-expired entry in the `search` cache region, THE Cache_Manager SHALL return that cached entry to the caller without invoking the Search_Service.
4. WHEN the Content_Aggregator completes a sync run that modifies at least one Content row, THE Cache_Manager SHALL evict every entry in the `search` cache region within 5 seconds of sync-run completion.
5. WHERE the application property `cache.search.enabled` is set to `false`, THE Cache_Manager SHALL omit caching of search responses and SHALL forward every search request to the Search_Service.
6. IF the Search_Service returns a non-success response for a search request, THEN THE Cache_Manager SHALL NOT store that response in the `search` cache region.
7. IF the cache backend is unavailable when reading or writing the `search` cache region, THEN THE Cache_Manager SHALL forward the request to the Search_Service and return the Search_Service response to the caller.

### Requirement 13: Rate Limiting

**User Story:** As an operator, I want public API endpoints rate-limited per client, so that the service is protected against abuse and provider overuse.

#### Acceptance Criteria

1. THE Rate_Limiter SHALL track request counts per client IP address for requests against `/api/v1/**`.
2. WHEN a client IP exceeds 100 requests within a 60-second sliding window, THE Rate_Limiter SHALL reject subsequent requests from that IP within the same window with HTTP 429.
3. WHEN the Rate_Limiter rejects a request, THE Rate_Limiter SHALL include a `Retry-After` header with the number of seconds until the client may retry.
4. THE Rate_Limiter SHALL expose its configured limit and window through the application properties `ratelimit.requests-per-window` and `ratelimit.window-seconds`.
5. WHERE the application property `ratelimit.enabled` is set to `false`, THE Rate_Limiter SHALL pass every request through without enforcement and SHALL emit an info-level log entry naming each client IP that would have been rejected, so that limit-tuning data is preserved.

### Requirement 14: Global Exception Handling

**User Story:** As an API consumer, I want consistent error responses, so that clients can reliably parse failures.

#### Acceptance Criteria

1. WHEN any controller in the Search_Engine_Service throws an exception, THE Exception_Handler SHALL convert the exception into a JSON body with the shape `{ "error": { "code": <string>, "message": <string> } }`.
2. WHEN a validation exception is thrown for a request parameter, THE Exception_Handler SHALL respond with HTTP 400 and `error.code` set to `INVALID_QUERY`.
3. WHEN a Content_Repository database error is thrown, THE Exception_Handler SHALL respond with HTTP 503 and `error.code` set to `DATABASE_UNAVAILABLE`.
4. WHEN a Provider_Adapter throws an exception that propagates to a request handler, THE Exception_Handler SHALL respond with HTTP 502 and `error.code` set to `PROVIDER_ERROR`.
5. WHEN any other unhandled exception reaches the Exception_Handler, THE Exception_Handler SHALL respond with HTTP 500 and `error.code` set to `INTERNAL_ERROR` and SHALL log the full stack trace via the Logger.

### Requirement 15: OpenAPI Documentation

**User Story:** As an API consumer, I want interactive API documentation, so that I can discover and try endpoints without reading source code.

#### Acceptance Criteria

1. THE OpenAPI_Module SHALL publish a machine-readable OpenAPI 3 document at `/v3/api-docs`.
2. THE OpenAPI_Module SHALL serve an interactive Swagger UI at `/swagger-ui.html`.
3. THE OpenAPI_Module SHALL document the Search_API endpoint with its query parameters, success response schema, and error response schema (matching the Exception_Handler error envelope).
4. THE OpenAPI_Module SHALL document at least one request example and one response example for the Search_API, and SHALL include additional examples covering distinct query parameter combinations (at minimum a keyword-only search, a type-filtered search, and a paginated search) where examples illustrate different behaviours.

### Requirement 16: Structured Logging

**User Story:** As an operator, I want structured logs across components, so that requests can be traced and incidents diagnosed.

#### Acceptance Criteria

1. THE Logger SHALL emit JSON-formatted log entries containing the fields `timestamp`, `level`, `logger`, `message`, and `requestId` when the entry originates inside an HTTP request scope.
2. WHEN the Search_API receives a request, THE Search_Engine_Service SHALL generate a unique `requestId` and SHALL place that identifier into the SLF4J MDC for the duration of the request.
3. WHEN the Content_Aggregator invokes a Provider_Adapter, THE Logger SHALL emit a log entry containing the provider name and the elapsed milliseconds for the fetch.
4. IF a Provider_Adapter, the Normalizer, the Scoring_Engine, or the Content_Repository emits an error-level log entry, THEN THE Logger SHALL include the offending field name, identifier, or external_id (when available) in the log payload.

### Requirement 17: Containerized Deployment

**User Story:** As a developer, I want a one-command local environment, so that I can run the service with its dependencies without manual setup.

#### Acceptance Criteria

1. THE Search_Engine_Service SHALL provide a `Dockerfile` that produces a runnable image of the Spring Boot application.
2. THE Search_Engine_Service SHALL provide a `docker-compose.yml` defining at least the services `app` and `postgres`.
3. WHERE the application uses Redis-backed caching, THE `docker-compose.yml` SHALL also define a `redis` service.
4. WHEN a developer runs `docker compose up` from the project root, THE Search_Engine_Service SHALL start with HTTP listening on the port configured by the `SERVER_PORT` environment variable (default 8080) and SHALL connect to the `postgres` service using the credentials supplied through environment variables.

### Requirement 18: Configuration and Secrets

**User Story:** As an operator, I want runtime configuration externalized, so that the service can be deployed across environments without code changes.

#### Acceptance Criteria

1. THE Search_Engine_Service SHALL load runtime configuration values for database connection (URL, username, password), provider endpoint URLs, cache settings, rate limit settings, and sync interval settings from Spring `application.yaml` and SHALL allow each value to be overridden by an environment variable of the same Spring property key, where environment variables take precedence over `application.yaml` values.
2. THE Search_Engine_Service SHALL omit credentials (database passwords, provider API keys, authentication tokens) and provider endpoint URLs from any file tracked in version control, and the project README SHALL list every environment variable the service reads, including its Spring property key, whether it is required or optional, and the expected value format.
3. WHEN the Search_Engine_Service starts, THE Search_Engine_Service SHALL validate that every property declared as required in the README is present and non-empty in the resolved configuration before initializing any component that depends on it.
4. IF one or more required configuration properties are missing or empty at startup, THEN THE Search_Engine_Service SHALL terminate startup within 10 seconds of detection, SHALL emit a startup error log entry that names each missing property by its Spring property key, SHALL exit with a non-zero process exit code, and SHALL NOT initialize the HTTP server, database connections, provider clients, or scheduled sync jobs.
5. IF the Search_Engine_Service writes configuration values to logs at any log level, THEN THE Search_Engine_Service SHALL replace the values of credential properties (database passwords, provider API keys, authentication tokens) with a fixed redaction marker so that the original secret value does not appear in log output.

### Requirement 19: Input Validation and Query Sanitization

**User Story:** As a security-conscious operator, I want all external input validated and sanitized, so that the service is protected against injection and malformed payloads.

#### Acceptance Criteria

1. WHEN the Search_API receives a request, THE Search_API SHALL validate every query parameter and request body field against the constraints declared in Requirements 8 and 9 before forwarding the request to the Search_Service.
2. IF any query parameter or request body field fails validation in criterion 1, THEN THE Search_API SHALL reject the request with an error response that identifies the offending field and the constraint that was violated, and SHALL NOT forward the request to the Search_Service.
3. WHEN the Search_Service constructs a database query from user-supplied input, THE Search_Service SHALL use parameterized queries or JPA criteria such that no user-supplied string is concatenated into raw SQL.
4. WHEN the Normalizer processes the `title` or `description` field of a record prior to persistence, THE Normalizer SHALL remove every Unicode general-category `Cc` control character except `\t` (U+0009), `\n` (U+000A), and `\r` (U+000D), and SHALL preserve all other characters byte-for-byte.
5. IF the combined size in bytes of a request body and query string is greater than or equal to 8193 (i.e., exceeds the 8 kilobyte = 8192 byte budget), THEN THE Search_API SHALL reject the request with HTTP 413 and SHALL NOT forward the request to the Search_Service.

### Requirement 20: Performance

**User Story:** As an API consumer, I want fast search responses under typical load, so that the service is usable in interactive applications.

#### Acceptance Criteria

1. WHILE the database holds at least 10,000 Content rows and the Search_API is under a sustained load of 50 requests per second for at least 5 continuous minutes on the reference environment defined in the README, WHEN the Search_API receives a search request whose cache key (`q`, `type`, `sort`, `page`, `limit`) is present in the `search` cache, THE Search_API SHALL complete the response within 300 milliseconds, measured from request receipt to response completion, at the 95th percentile.
2. WHILE the database holds at least 10,000 Content rows and the Search_API is under a sustained load of 50 requests per second for at least 5 continuous minutes on the reference environment defined in the README, WHEN the Search_API receives a search request whose cache key (`q`, `type`, `sort`, `page`, `limit`) is not present in the `search` cache, THE Search_API SHALL complete the response within 1000 milliseconds, measured from request receipt to response completion, at the 95th percentile.
3. WHEN the Search_Service processes a search request, THE Search_Service SHALL retrieve at most `limit` Content rows from the database per request, regardless of the total number of matching rows.
4. WHEN performance measurements are collected for the cached and non-cached response time criteria, THE Search_API SHALL discard the first 30 seconds of the load run as a warm-up period and compute the 95th percentile over a continuous measurement window of at least 5 minutes following the warm-up.

### Requirement 21: Reliability and Provider Isolation

**User Story:** As an operator, I want provider failures isolated, so that one failing provider does not degrade the rest of the service.

#### Acceptance Criteria

1. WHEN a Provider_Adapter HTTP call exceeds 10 seconds without a response, THE Provider_Adapter SHALL abort the call and SHALL return an empty list for the current sync run.
2. WHEN a Provider_Adapter HTTP call fails with a transient error (HTTP 5xx, connection reset, or read timeout), THE Provider_Adapter SHALL retry the call up to 2 additional times with exponential backoff starting at 500 milliseconds.
3. IF all retry attempts for a Provider_Adapter fail within a sync run, THEN THE Provider_Adapter SHALL return an empty list and SHALL log the failure without raising an exception that aborts other Provider_Adapters.
4. WHEN the Search_API is invoked while the database is unreachable, THE Search_API SHALL respond according to Requirement 14 clause 3 within 2000 milliseconds at the 95th percentile.

### Requirement 22: Maintainability and Architecture

**User Story:** As an architect, I want a Clean Architecture layout enforced, so that business logic remains independent of frameworks and infrastructure.

#### Acceptance Criteria

1. THE Search_Engine_Service SHALL organize source code into the packages `domain`, `application`, `infrastructure`, and `web` such that classes in `domain` depend on no other application package.
2. THE Scoring_Engine SHALL reside in the `domain` package and SHALL not import from `org.springframework`, `jakarta.persistence`, `com.fasterxml.jackson`, or any other framework package.
3. THE Content_Repository interface SHALL reside in the `domain` package, while its concrete implementation SHALL reside in the `infrastructure` package.
4. THE Search_API controller SHALL reside in the `web` package and SHALL invoke only `application` package services, never `infrastructure` package classes directly.
5. THE Search_Engine_Service SHALL use DTO classes (distinct from `Content`) for HTTP request and response payloads.

### Requirement 23: Automated Testing

**User Story:** As an engineer, I want automated tests covering business logic and integration boundaries, so that regressions are caught before deployment.

#### Acceptance Criteria

1. THE Search_Engine_Service SHALL include JUnit 5 unit tests for the Scoring_Engine that, for both `video` and `text` Content_Type values, assert the computed Base_Score, Type_Multiplier, Engagement_Score, Freshness_Score, and Final_Score equal the expected values within an absolute tolerance of 0.0001, with at least one test case per scoring component.
2. THE Search_Engine_Service SHALL include JUnit 5 unit tests for the Normalizer that (a) assert successful mapping to the canonical DTO for one valid payload per provider with every documented field populated, and (b) assert that each invalid input case enumerated in Requirement 4 causes the Normalizer to reject the input by raising a validation error identifying the offending field, with one dedicated test method per invalid case.
3. WHEN the integration test suite executes, THE Search_Engine_Service SHALL boot a Spring application context backed by a Testcontainers PostgreSQL instance (or an equivalent embedded PostgreSQL) seeded with a deterministic fixture dataset, and SHALL exercise the Search_API end-to-end with at least one keyword search, one type-filtered search, and one paginated search, asserting for each call that the response status indicates success and that the returned result set matches the expected items, ordering, and pagination metadata derived from the fixture.
4. THE Search_Engine_Service SHALL include a Round_Trip_Property test using a property-based testing framework (e.g., jqwik) that, for both XML and JSON provider payloads, generates at least 100 payload instances per provider covering every documented field from Requirement 4 and asserts that parsing each generated payload and then re-serializing the resulting DTO produces a payload whose value for every documented field is equal to the corresponding value in the original payload.
5. THE Search_Engine_Service SHALL configure the Maven build so that the unit, integration, and property tests defined in criteria 1 through 4 are executed during the `verify` phase, and IF any executed test fails, THEN THE Maven build SHALL terminate with a non-zero exit status and not produce a successful `verify` outcome.
