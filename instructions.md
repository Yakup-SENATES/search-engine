# `PROJECT_SPEC.md`

## Spec-Driven Development Blueprint for Amazon Kiro

### Search Engine Aggregation Service Case Study

---

# 1. Project Overview

## Project Name

**Content Aggregation & Search Ranking Platform**

## Objective

Develop a scalable backend service that aggregates content from multiple providers (JSON/XML), normalizes the data, calculates ranking scores, and exposes searchable APIs with filtering, pagination, and ranking support.

An optional dashboard interface should display indexed content with sorting capabilities.

---

# 2. Product Vision

The system acts as a unified search platform that:

* Fetches content from heterogeneous providers
* Converts provider-specific formats into a unified domain model
* Stores normalized content
* Calculates dynamic ranking scores
* Serves optimized search results
* Supports future provider integrations without major refactoring

---

# 3. Core Business Goals

## Primary Goals

* Unified search experience
* Extensible provider architecture
* Deterministic scoring algorithm
* Fast search response times
* High maintainability

## Secondary Goals

* Dashboard visualization
* Operational observability
* Caching
* Async synchronization
* Scalability readiness

---

# 4. Functional Requirements

---

## FR-1 Content Aggregation

### Description

System must retrieve content from multiple providers.

### Acceptance Criteria

* Supports:

  * Provider 1 (JSON)
  * Provider 2 (XML)
* Provider responses are normalized
* Failures in one provider do not break ingestion
* Providers are independently configurable

---

## FR-2 Search API

### Endpoint

```http
GET /api/v1/search
```

### Query Parameters

| Parameter | Type                             | Required | Description    |
| --------- | -------------------------------- | -------- | -------------- |
| q         | string                           | yes      | Search keyword |
| type      | enum(video,text)                 | no       | Content filter |
| sort      | enum(score,popularity,relevance) | no       | Sorting        |
| page      | int                              | no       | Pagination     |
| limit     | int                              | no       | Page size      |

---

### Acceptance Criteria

* Keyword search works
* Type filtering works
* Sorting works
* Pagination works
* Empty results handled gracefully

---

## FR-3 Ranking Engine

### Formula

```text
Final Score =
(Base Score * Content Type Multiplier)
+ Freshness Score
+ Engagement Score
```

---

### Video Formula

```text
Base Score = views / 1000 + likes / 100
Multiplier = 1.5
Engagement = (likes / views) * 10
```

---

### Text Formula

```text
Base Score = reading_time + reactions / 50
Multiplier = 1.0
Engagement = (reactions / reading_time) * 5
```

---

### Freshness

| Age        | Score |
| ---------- | ----- |
| < 1 week   | +5    |
| < 1 month  | +3    |
| < 3 months | +1    |
| older      | +0    |

---

### Acceptance Criteria

* Scores are deterministic
* Formula isolated in domain service
* Unit tested independently
* Extensible for future scoring strategies

---

## FR-4 Data Persistence

### Requirements

* Persist normalized content
* Avoid duplicates
* Maintain provider references
* Preserve raw payloads optionally

---

## FR-5 Dashboard

### Features

* Content listing
* Sort by score
* Filter by type
* Pagination

### Display Fields

* Title
* Type
* Score

---

# 5. Non-Functional Requirements

---

## Performance

* Search response under 300ms (cached)
* Under 1s uncached
* Pagination optimized

---

## Scalability

Architecture should support:

* Multiple providers
* Horizontal scaling
* Queue-based ingestion
* Distributed cache

---

## Reliability

* Provider failures isolated
* Retry policies
* Graceful degradation

---

## Maintainability

* SOLID principles
* Clean architecture
* Domain separation
* Provider abstraction

---

## Security

* Input validation
* Query sanitization
* Rate limiting
* Secure secrets management

---

# 6. Suggested Architecture

# Preferred Stack (Recommended)

## Backend

### Recommended: Go

Reasoning:

* Excellent concurrency
* Lightweight APIs
* Fast XML/JSON parsing
* Easy scaling
* Ideal for provider ingestion

Alternative:

* .NET Core
* Symfony

---

## Database

### Recommended: PostgreSQL

Reasoning:

* Full-text search support
* JSON columns
* Strong indexing
* ACID guarantees

---

## Cache

### Recommended: Redis

Use Cases:

* Search result caching
* Provider response caching
* Rate-limit counters

---

## Queue (Optional Bonus)

### Recommended

* RabbitMQ
  OR
* Kafka

Use Cases:

* Async provider sync
* Score recalculation
* Retry processing

---

# 7. Recommended Architecture Style

## Clean Architecture

```text
cmd/
internal/
  domain/
  application/
  infrastructure/
  interfaces/
```

---

# 8. Bounded Contexts

| Context   | Responsibility        |
| --------- | --------------------- |
| Provider  | External integrations |
| Search    | Search orchestration  |
| Ranking   | Score calculations    |
| Content   | Content lifecycle     |
| Dashboard | UI rendering          |

---

# 9. Domain Model

---

## Content Entity

```go
type Content struct {
    ID
    ProviderID
    ExternalID

    Title
    Description

    Type

    Score
    PopularityScore
    RelevanceScore

    Views
    Likes
    ReadingTime
    Reactions

    PublishedAt
    CreatedAt
    UpdatedAt
}
```

---

# 10. Provider Abstraction Design

## Goal

New providers must be added without changing existing logic.

---

## Interface

```go
type Provider interface {
    Fetch(ctx context.Context) ([]ProviderContent, error)
    Name() string
}
```

---

## Implementations

```text
providers/
  json_provider/
  xml_provider/
```

---

# 11. Normalization Layer

## Objective

Transform heterogeneous provider data into unified internal entities.

---

## Requirements

* XML parsing
* JSON parsing
* Field mapping
* Validation
* Data sanitization

---

# 12. Search Strategy

## Recommended

### PostgreSQL Full Text Search

OR

### Elasticsearch (Bonus)

---

## Search Features

* Title matching
* Description matching
* Weighted fields
* Ranking boosts

---

# 13. Ranking Engine Specification

## Requirements

* Pure deterministic service
* No infrastructure dependencies
* Independently testable

---

## Interface

```go
type ScoreCalculator interface {
    Calculate(content Content) float64
}
```

---

# 14. Database Design

---

## contents

| Field        | Type      |
| ------------ | --------- |
| id           | uuid      |
| provider     | varchar   |
| external_id  | varchar   |
| title        | text      |
| description  | text      |
| type         | varchar   |
| score        | numeric   |
| views        | int       |
| likes        | int       |
| reading_time | int       |
| reactions    | int       |
| published_at | timestamp |

---

## indexes

```sql
CREATE INDEX idx_contents_type ON contents(type);
CREATE INDEX idx_contents_score ON contents(score DESC);
CREATE INDEX idx_contents_search ON contents USING gin(to_tsvector('english', title || ' ' || description));
```

---

# 15. API Specification

---

## Search Endpoint

```http
GET /api/v1/search
```

---

## Example Response

```json
{
  "data": [
    {
      "id": "uuid",
      "title": "Example Video",
      "type": "video",
      "score": 12.5
    }
  ],
  "pagination": {
    "page": 1,
    "limit": 10,
    "total": 120
  }
}
```

---

# 16. Error Handling Strategy

---

## Standard Error Response

```json
{
  "error": {
    "code": "INVALID_QUERY",
    "message": "Query parameter is required"
  }
}
```

---

## Error Categories

| Type       | Example            |
| ---------- | ------------------ |
| Validation | invalid query      |
| Provider   | timeout            |
| Database   | unavailable        |
| Internal   | unexpected failure |

---

# 17. Rate Limiting

## Requirements

* Prevent provider abuse
* Prevent API abuse

---

## Recommended

```text
100 requests / minute / IP
```

Redis-backed token bucket preferred.

---

# 18. Cache Strategy

---

## Search Cache

### Key

```text
search:{query}:{type}:{sort}:{page}
```

### TTL

```text
5 minutes
```

---

## Provider Cache

Optional:

* cache raw provider responses

---

# 19. Background Jobs

---

## Recommended Jobs

| Job              | Purpose               |
| ---------------- | --------------------- |
| Provider sync    | Fetch new data        |
| Re-score content | Recalculate freshness |
| Cleanup          | Remove stale cache    |

---

# 20. Testing Strategy

---

## Unit Tests

Coverage Targets:

* Ranking engine
* Search filters
* Provider parsers
* Normalizers

---

## Integration Tests

* DB integration
* Provider integration
* API tests

---

## E2E Tests

* Full ingestion flow
* Search flow

---

# 21. Observability

---

## Logging

Structured JSON logs.

Fields:

* request_id
* provider
* latency
* status

---

## Metrics

Recommended:

* request count
* provider latency
* cache hit rate
* ingestion failures

---

## Tracing (Bonus)

OpenTelemetry support.

---

# 22. Dashboard Specification

---

## Suggested Stack

### Frontend

* Next.js
  OR
* React + Vite

---

## Dashboard Features

### Table View

| Field |
| ----- |
| Title |
| Type  |
| Score |

---

## Filters

* Content type
* Sorting
* Search input

---

# 23. API Documentation

---

## Required

### OpenAPI / Swagger

Endpoints documented with:

* examples
* response schemas
* error schemas

---

# 24. CI/CD Expectations

---

## Pipeline

### Required Steps

```text
lint
test
build
dockerize
```

---

## Bonus

* automatic deployment
* preview environments

---

# 25. Dockerization

---

## Required

* Dockerfile
* docker-compose.yml

---

## Services

* app
* postgres
* redis

---

# 26. README Requirements

---

## Must Include

* Setup instructions
* Architecture decisions
* Tradeoffs
* API examples
* Environment variables
* Run commands

---

# 27. Folder Structure (Recommended)

```text
project/
  cmd/
  internal/
    domain/
    application/
    infrastructure/
    interfaces/
  migrations/
  scripts/
  docs/
  deployments/
```

---

# 28. Coding Standards

---

## Requirements

* Small functions
* Dependency injection
* Interface-driven development
* No business logic in controllers

---

# 29. Kiro Agent Instructions

## IMPORTANT

Kiro must behave as a senior backend architect.

---

## Kiro Development Rules

### MUST

* Follow Clean Architecture
* Generate testable code
* Use interfaces for external systems
* Keep business logic inside domain layer
* Avoid framework lock-in
* Use DTOs between layers
* Add unit tests for all business logic
* Add OpenAPI annotations
* Use repository pattern

---

## MUST NOT

* Put SQL inside handlers
* Couple providers to domain logic
* Hardcode provider implementations
* Use global mutable state

---

# 30. Kiro Task Decomposition

---

## Epic 1 — Foundation

### Tasks

* Setup project structure
* Configure Docker
* Configure PostgreSQL
* Configure Redis

---

## Epic 2 — Provider Integration

### Tasks

* JSON provider client
* XML provider client
* Retry policies
* Normalization

---

## Epic 3 — Ranking Engine

### Tasks

* Formula implementation
* Freshness scoring
* Engagement scoring
* Unit tests

---

## Epic 4 — Search API

### Tasks

* Search endpoint
* Pagination
* Filtering
* Sorting

---

## Epic 5 — Dashboard

### Tasks

* Table rendering
* Filters
* Sorting UI

---

# 31. Definition of Done

A feature is complete only if:

* Tests pass
* Swagger updated
* Logs added
* Errors handled
* README updated
* Docker works
* No lint errors

---

# 32. Bonus Features

---

## Recommended Bonuses

* Elasticsearch integration
* CQRS
* Async ingestion
* Event-driven architecture
* AI-based relevance scoring
* Semantic search
* Trending content engine

---

# 33. Recommended Development Order

```text
1. Project setup
2. Database
3. Provider integrations
4. Normalization
5. Ranking engine
6. Persistence
7. Search API
8. Cache
9. Dashboard
10. Observability
```

---

# 34. Suggested Git Strategy

---

## Branches

```text
main
develop
feature/*
```

---

## Commit Convention

```text
feat:
fix:
refactor:
test:
docs:
```

---

# 35. Final Implementation Expectations

The final system should demonstrate:

* Strong architectural thinking
* Extensibility
* Correct scoring logic
* Clean abstractions
* Production-ready engineering mindset
* Maintainability over feature quantity

---

# 36. Final Kiro Prompt

```text
You are a senior backend architect and staff-level engineer.

Build a production-grade Content Aggregation & Search Ranking platform using Clean Architecture principles.

Requirements:
- Integrate JSON and XML providers
- Normalize provider data
- Persist content in PostgreSQL
- Implement ranking formula
- Provide searchable REST API
- Add pagination/filtering/sorting
- Add Redis caching
- Ensure extensibility for future providers
- Add Swagger/OpenAPI docs
- Add unit/integration tests
- Use repository pattern
- Use dependency injection
- Dockerize the application

Important constraints:
- Keep business logic framework-independent
- Use interfaces for providers and repositories
- Avoid tight coupling
- Ensure high testability
- Implement graceful error handling
- Add structured logging
- Follow SOLID principles

Generate:
- Architecture
- Folder structure
- Interfaces
- Entities
- DTOs
- Migrations
- API handlers
- Services
- Repositories
- Tests
- Docker setup
- README
- Swagger docs
```


# Provider Integration Specification Addendum

## Add this section into `PROJECT_SPEC.md`

---

# 37. Real Provider Payload Specifications

This section defines the actual mock provider contracts and normalization rules.

---

# 37.1 Provider 1 — JSON Provider

## Provider Characteristics

| Property       | Value |
| -------------- | ----- |
| Format         | JSON  |
| Content Types  | video |
| Pagination     | yes   |
| Nested Metrics | yes   |

---

## Example Payload

```json id="0c73q2"
{
  "contents": [
    {
      "id": "v1",
      "title": "Go Programming Tutorial",
      "type": "video",
      "metrics": {
        "views": 15000,
        "likes": 1200,
        "duration": "15:30"
      },
      "published_at": "2024-03-15T10:00:00Z",
      "tags": ["programming", "tutorial"]
    }
  ],
  "pagination": {
    "total": 150,
    "page": 1,
    "per_page": 10
  }
}
```

---

# 37.2 Provider 1 Parsing Rules

## Mapping Rules

| Provider Field   | Internal Field |
| ---------------- | -------------- |
| id               | external_id    |
| title            | title          |
| type             | type           |
| metrics.views    | views          |
| metrics.likes    | likes          |
| metrics.duration | duration       |
| published_at     | published_at   |
| tags             | tags           |

---

# 37.3 Provider 1 DTO

```go id="6pnfxy"
type Provider1Response struct {
    Contents   []Provider1Content `json:"contents"`
    Pagination PaginationDTO      `json:"pagination"`
}

type Provider1Content struct {
    ID          string          `json:"id"`
    Title       string          `json:"title"`
    Type        string          `json:"type"`
    Metrics     Provider1Metric `json:"metrics"`
    PublishedAt time.Time       `json:"published_at"`
    Tags        []string        `json:"tags"`
}

type Provider1Metric struct {
    Views   int    `json:"views"`
    Likes   int    `json:"likes"`
    Duration string `json:"duration"`
}
```

---

# 37.4 Provider 1 Normalization Example

## Input

```json id="7lf18u"
{
  "id": "v1",
  "title": "Go Programming Tutorial",
  "type": "video",
  "metrics": {
    "views": 15000,
    "likes": 1200
  }
}
```

---

## Normalized Output

```json id="u0ic86"
{
  "provider": "provider1",
  "external_id": "v1",
  "title": "Go Programming Tutorial",
  "type": "video",
  "views": 15000,
  "likes": 1200
}
```

---

# 38. Provider 2 — XML Provider

## Provider Characteristics

| Property      | Value          |
| ------------- | -------------- |
| Format        | XML            |
| Content Types | video, article |
| Nested Stats  | yes            |
| Categories    | XML list       |

---

## Example XML

```xml id="m4nqik"
<?xml version="1.0" encoding="UTF-8"?>
<feed>
  <items>
    <item>
      <id>v1</id>
      <headline>Introduction to Docker</headline>
      <type>video</type>
    </item>
  </items>
</feed>
```

---

# 38.1 Provider 2 Parsing Rules

## Mapping Rules

| XML Field           | Internal Field |
| ------------------- | -------------- |
| id                  | external_id    |
| headline            | title          |
| type                | type           |
| stats.views         | views          |
| stats.likes         | likes          |
| stats.reading_time  | reading_time   |
| stats.reactions     | reactions      |
| publication_date    | published_at   |
| categories.category | tags           |

---

# 38.2 XML DTO Structure

```go id="t0o8kr"
type XMLFeed struct {
    XMLName xml.Name  `xml:"feed"`
    Items   XMLItems  `xml:"items"`
    Meta    XMLMeta   `xml:"meta"`
}

type XMLItems struct {
    Items []XMLItem `xml:"item"`
}

type XMLItem struct {
    ID              string       `xml:"id"`
    Headline        string       `xml:"headline"`
    Type            string       `xml:"type"`
    Stats           XMLStats     `xml:"stats"`
    PublicationDate string       `xml:"publication_date"`
    Categories      XMLCategories `xml:"categories"`
}

type XMLStats struct {
    Views       int `xml:"views"`
    Likes       int `xml:"likes"`
    ReadingTime int `xml:"reading_time"`
    Reactions   int `xml:"reactions"`
    Comments    int `xml:"comments"`
}

type XMLCategories struct {
    Categories []string `xml:"category"`
}
```

---

# 38.3 XML Type Mapping Rules

## Important

Provider 2 returns:

```text id="qubh8h"
article
```

Internal system must normalize this into:

```text id="2exk2s"
text
```

---

## Mapping Table

| Provider Type | Internal Type |
| ------------- | ------------- |
| video         | video         |
| article       | text          |

---

# 39. Unified Internal Content Schema

All providers must normalize into this structure.

```go id="1pzzhr"
type Content struct {
    ID              uuid.UUID
    Provider        string
    ExternalID      string

    Title           string
    Description     string

    Type            string

    Views           int
    Likes           int

    ReadingTime     int
    Reactions       int

    Duration        string

    Tags            []string

    PublishedAt     time.Time

    FinalScore      float64
    PopularityScore float64
    RelevanceScore  float64

    CreatedAt       time.Time
    UpdatedAt       time.Time
}
```

---

# 40. Provider Adapter Architecture

## Goal

Providers must be plug-and-play.

---

# Recommended Structure

```text id="gn22d0"
internal/
  providers/
    contracts/
    provider1/
    provider2/
    normalizers/
```

---

# Provider Interface

```go id="p4w4w4"
type Provider interface {
    Fetch(ctx context.Context, page int) ([]Content, error)
    Name() string
}
```

---

# 41. Provider Normalizer Pattern

## Recommended Pattern

Each provider should contain:

```text id="8pbyyk"
client.go
dto.go
mapper.go
parser.go
provider.go
```

---

# 42. XML Parsing Requirements

## Requirements

* Support malformed XML handling
* Ignore unknown fields
* Graceful fallback on missing optional nodes

---

# 43. Duplicate Prevention Strategy

## Problem

Providers may send same content repeatedly.

---

## Recommended Unique Constraint

```sql id="8h1uwo"
UNIQUE(provider, external_id)
```

---

# 44. Sync Strategy

## Recommended

### Scheduled Pull

```text id="e58miv"
Every 5 minutes
```

---

## Workflow

```text id="p3j0qy"
Fetch Provider Data
→ Normalize
→ Validate
→ Calculate Score
→ Upsert Database
→ Invalidate Cache
```

---

# 45. Upsert Strategy

## Recommended SQL

```sql id="8kzv0m"
INSERT INTO contents (...)
VALUES (...)
ON CONFLICT (provider, external_id)
DO UPDATE SET
  score = EXCLUDED.score,
  updated_at = NOW();
```

---

# 46. Validation Rules

---

## Required Fields

| Field        | Rule       |
| ------------ | ---------- |
| title        | non-empty  |
| type         | valid enum |
| published_at | valid date |
| external_id  | required   |

---

## Metric Rules

| Field     | Rule |
| --------- | ---- |
| views     | >= 0 |
| likes     | >= 0 |
| reactions | >= 0 |

---

# 47. Score Calculation Examples

---

# Example 1 — Video

Input:

```json id="r4s6cc"
{
  "views": 15000,
  "likes": 1200,
  "type": "video"
}
```

Calculation:

```text id="t5fdg5"
Base Score:
15000 / 1000 + 1200 / 100
= 15 + 12
= 27

Weighted:
27 * 1.5
= 40.5

Engagement:
(1200 / 15000) * 10
= 0.8

Freshness:
+5

Final:
46.3
```

---

# Example 2 — Text

Input:

```json id="jlc4u7"
{
  "reading_time": 8,
  "reactions": 450,
  "type": "text"
}
```

Calculation:

```text id="t0j0ji"
Base:
8 + (450 / 50)
= 17

Weighted:
17 * 1.0
= 17

Engagement:
(450 / 8) * 5
= 281.25

Freshness:
+5

Final:
303.25
```

---

# 48. Important Engineering Observation

Current text engagement formula can produce disproportionately high scores.

---

# Recommendation

Normalize engagement score.

Example:

```text id="d7hb4s"
Min(engagement, 20)
```

OR

```text id="vdyfww"
logarithmic scaling
```

---

# 49. Recommended Architectural Decision Records (ADR)

---

## ADR-001

### Use Clean Architecture

Reason:

* maintainability
* testability
* provider extensibility

---

## ADR-002

### Use PostgreSQL

Reason:

* indexing
* full-text search
* reliability

---

## ADR-003

### Use Redis Cache

Reason:

* fast search retrieval
* scalable cache invalidation

---

# 50. Strong Recommendation for Case Study Success

Prioritize:

* architecture quality
* clean abstractions
* testing
* maintainability

Over:

* excessive feature count
* premature optimization
* overengineering

The evaluation will likely focus more on:

* engineering maturity
* code organization
* extensibility
* decision quality
  than raw feature quantity.
