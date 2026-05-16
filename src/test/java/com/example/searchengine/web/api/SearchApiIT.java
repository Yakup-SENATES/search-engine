package com.example.searchengine.web.api;

import com.example.searchengine.domain.content.Content;
import com.example.searchengine.domain.content.ContentRepository;
import com.example.searchengine.domain.content.ContentType;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring Boot integration test for the {@code GET /api/v1/search} endpoint.
 *
 * <p>Boots a {@link PostgreSQLContainer} of {@code postgres:16}, lets Flyway apply
 * the schema (V1__init.sql), and seeds deterministic fixtures via the
 * {@link ContentRepository} port before each scenario. {@link TestRestTemplate}
 * drives real HTTP requests against the embedded server (RANDOM_PORT) so the
 * full controller, validation, exception-handler, and persistence stack is
 * exercised.</p>
 *
 * <p>Validates: Requirements 8.1, 8.2, 8.3, 8.4, 8.5, 9.1, 9.2, 9.3, 9.4, 9.5,
 * 9.6, 9.7, 9.8, 9.9, 9.10, 9.11.</p>
 *
 * <p>Scenarios:
 * <ol>
 *   <li>Keyword search — only matching titles returned.</li>
 *   <li>Type-filtered search — only items of the requested type returned.</li>
 *   <li>Paginated search — correct slice and pagination metadata.</li>
 *   <li>Sort variations — {@code score}, {@code popularity}, {@code relevance}
 *       all return content in the documented order.</li>
 *   <li>Empty result — 200 with empty {@code data} and {@code total=0}.</li>
 *   <li>Invalid query parameters — 400 with {@code INVALID_QUERY} envelope
 *       for bad {@code q}, {@code type}, {@code sort}, {@code page}, {@code limit}.</li>
 * </ol>
 *
 * <p>External dependencies that are not exercised by these scenarios are disabled
 * via {@link DynamicPropertySource}: cache (Redis), rate limiter, sync scheduler,
 * and the provider HTTP clients (placeholder URLs).</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class SearchApiIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16")
                    .withDatabaseName("searchengine_test")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        // Wire the JDBC URL and credentials from the container so Flyway and
        // Hibernate validate against the same database (REQ 5.1, 5.4, 5.5).
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        // The Search API is exercised directly; nothing in the test path should
        // trigger an outbound HTTP call, scheduled sync, or Redis traffic.
        registry.add("aggregator.sync.enabled", () -> "false");
        registry.add("cache.search.enabled", () -> "false");
        registry.add("ratelimit.enabled", () -> "false");

        // Required configuration keys for ProviderProperties; the values are
        // never dereferenced because the JSON/XML adapters are not invoked.
        registry.add("providers.json.base-url", () -> "http://localhost:0/json");
        registry.add("providers.xml.base-url", () -> "http://localhost:0/xml");
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ContentRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.execute("DELETE FROM contents");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Scenario 1: Keyword search returns only matching rows
    // Requirements 8.1, 8.2, 8.5, 9.10, 9.11
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("keyword search returns only rows whose title or description matches q")
    void keywordSearch_returnsOnlyMatchingRows() {
        seed("kw-1", "puppy adventures in the park", null,         ContentType.VIDEO, 50.0, 50.0);
        seed("kw-2", "kitten compilation",            null,         ContentType.VIDEO, 40.0, 40.0);
        seed("kw-3", "java tutorial",                 null,         ContentType.TEXT,  30.0, 30.0);
        seed("kw-4", "python tutorial",               null,         ContentType.TEXT,  20.0, 20.0);

        ResponseEntity<SearchResponse> response = restTemplate.getForEntity(
                "/api/v1/search?q=puppy", SearchResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        SearchResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.data()).hasSize(1);
        assertThat(body.data().get(0).title()).isEqualTo("puppy adventures in the park");
        assertThat(body.pagination().total()).isEqualTo(1);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Scenario 2: Type-filtered search
    // Requirements 9.1, 9.2, 9.10
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("type=video filter returns only video items")
    void typeFilteredSearch_returnsOnlyVideos() {
        seed("tf-1", "tutorial alpha",   null, ContentType.VIDEO, 90.0, 90.0);
        seed("tf-2", "tutorial bravo",   null, ContentType.VIDEO, 80.0, 80.0);
        seed("tf-3", "tutorial charlie", null, ContentType.TEXT,  70.0, 70.0);
        seed("tf-4", "tutorial delta",   null, ContentType.TEXT,  60.0, 60.0);

        ResponseEntity<SearchResponse> response = restTemplate.getForEntity(
                "/api/v1/search?q=tutorial&type=video", SearchResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        SearchResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.data()).hasSize(2);
        assertThat(body.data())
                .extracting(ContentSummaryDto::type)
                .containsOnly("video");
        assertThat(body.pagination().total()).isEqualTo(2);
    }

    @Test
    @DisplayName("type=text filter returns only text items")
    void typeFilteredSearch_returnsOnlyText() {
        seed("tt-1", "lesson one",   null, ContentType.VIDEO, 90.0, 90.0);
        seed("tt-2", "lesson two",   null, ContentType.TEXT,  80.0, 80.0);
        seed("tt-3", "lesson three", null, ContentType.TEXT,  70.0, 70.0);

        ResponseEntity<SearchResponse> response = restTemplate.getForEntity(
                "/api/v1/search?q=lesson&type=text", SearchResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        SearchResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.data()).hasSize(2);
        assertThat(body.data())
                .extracting(ContentSummaryDto::type)
                .containsOnly("text");
        assertThat(body.pagination().total()).isEqualTo(2);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Scenario 3: Paginated search
    // Requirements 9.7, 9.8, 9.10
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("page=2&limit=5 returns the second slice with correct pagination metadata")
    void paginatedSearch_returnsCorrectSliceAndMetadata() {
        // Seed 12 rows with strictly decreasing finalScore so the default
        // sort=score order is fully deterministic.
        for (int i = 1; i <= 12; i++) {
            String externalId = String.format("pg-%02d", i);
            String title = "uniqueword item " + String.format("%02d", i);
            double score = 1000.0 - i; // i=1 → 999, i=2 → 998, …, i=12 → 988
            seed(externalId, title, null, ContentType.VIDEO, score, score);
        }

        ResponseEntity<SearchResponse> response = restTemplate.getForEntity(
                "/api/v1/search?q=uniqueword&page=2&limit=5", SearchResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        SearchResponse body = response.getBody();
        assertThat(body).isNotNull();

        // page=2, limit=5 → items 6..10 in the global score-sorted order.
        assertThat(body.data()).hasSize(5);
        assertThat(body.data())
                .extracting(ContentSummaryDto::title)
                .containsExactly(
                        "uniqueword item 06",
                        "uniqueword item 07",
                        "uniqueword item 08",
                        "uniqueword item 09",
                        "uniqueword item 10");

        assertThat(body.pagination().page()).isEqualTo(2);
        assertThat(body.pagination().limit()).isEqualTo(5);
        assertThat(body.pagination().total()).isEqualTo(12);
    }

    @Test
    @DisplayName("missing page and limit fall back to defaults page=1, limit=10")
    void paginatedSearch_defaultsApplied() {
        for (int i = 1; i <= 12; i++) {
            String externalId = String.format("dp-%02d", i);
            String title = "defaultpage item " + String.format("%02d", i);
            double score = 1000.0 - i;
            seed(externalId, title, null, ContentType.VIDEO, score, score);
        }

        ResponseEntity<SearchResponse> response = restTemplate.getForEntity(
                "/api/v1/search?q=defaultpage", SearchResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        SearchResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.data()).hasSize(10);
        assertThat(body.pagination().page()).isEqualTo(1);
        assertThat(body.pagination().limit()).isEqualTo(10);
        assertThat(body.pagination().total()).isEqualTo(12);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Scenario 4: Sort variations (score, popularity, relevance)
    // Requirements 9.3, 9.4, 9.5, 9.6
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("sort=score orders results by final_score descending")
    void sort_byScore() {
        seedSortFixture();

        ResponseEntity<SearchResponse> response = restTemplate.getForEntity(
                "/api/v1/search?q=sortable&sort=score", SearchResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        SearchResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.data())
                .extracting(ContentSummaryDto::title)
                .containsExactly("sortable alpha", "sortable beta", "sortable gamma");
    }

    @Test
    @DisplayName("sort=popularity orders results by popularity_score descending")
    void sort_byPopularity() {
        seedSortFixture();

        ResponseEntity<SearchResponse> response = restTemplate.getForEntity(
                "/api/v1/search?q=sortable&sort=popularity", SearchResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        SearchResponse body = response.getBody();
        assertThat(body).isNotNull();
        // popularityScore: alpha=10, beta=20, gamma=30 → DESC: gamma, beta, alpha
        assertThat(body.data())
                .extracting(ContentSummaryDto::title)
                .containsExactly("sortable gamma", "sortable beta", "sortable alpha");
    }

    @Test
    @DisplayName("sort=relevance returns the same set of matches with the documented metadata")
    void sort_byRelevance() {
        seedSortFixture();

        ResponseEntity<SearchResponse> response = restTemplate.getForEntity(
                "/api/v1/search?q=sortable&sort=relevance", SearchResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        SearchResponse body = response.getBody();
        assertThat(body).isNotNull();
        // sort=relevance still returns every matching row; the implementation
        // ranks by ts_rank_cd at query time. We don't assert the exact order
        // here because relevance ranking depends on PostgreSQL's tsvector
        // statistics, but the result set must contain the three seeded titles.
        assertThat(body.data())
                .extracting(ContentSummaryDto::title)
                .containsExactlyInAnyOrder(
                        "sortable alpha", "sortable beta", "sortable gamma");
        assertThat(body.pagination().total()).isEqualTo(3);
    }

    @Test
    @DisplayName("absent sort defaults to score (REQ 9.6)")
    void sort_defaultsToScore() {
        seedSortFixture();

        ResponseEntity<SearchResponse> response = restTemplate.getForEntity(
                "/api/v1/search?q=sortable", SearchResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        SearchResponse body = response.getBody();
        assertThat(body).isNotNull();
        // Default == sort=score → finalScore desc: alpha (100), beta (50), gamma (25)
        assertThat(body.data())
                .extracting(ContentSummaryDto::title)
                .containsExactly("sortable alpha", "sortable beta", "sortable gamma");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Scenario 5: Empty result
    // Requirement 9.11
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("query that matches nothing returns 200 with empty data and total=0")
    void emptyResult_returns200WithEmptyData() {
        seed("er-1", "alpha", null, ContentType.VIDEO, 10.0, 10.0);
        seed("er-2", "beta",  null, ContentType.TEXT,  5.0,  5.0);

        ResponseEntity<SearchResponse> response = restTemplate.getForEntity(
                "/api/v1/search?q=zzznoneofthesexyz", SearchResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        SearchResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.data()).isEmpty();
        assertThat(body.pagination().total()).isZero();
        assertThat(body.pagination().page()).isEqualTo(1);
        assertThat(body.pagination().limit()).isEqualTo(10);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Scenario 6: Invalid query parameters → 400 INVALID_QUERY
    // Requirements 8.3, 8.4, 9.2, 9.9, 14.1, 14.2
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("missing q returns 400 INVALID_QUERY")
    void invalidQuery_missingQ() {
        assertInvalidQuery(restTemplate.getForEntity("/api/v1/search", String.class));
    }

    @Test
    @DisplayName("blank q returns 400 INVALID_QUERY")
    void invalidQuery_blankQ() {
        assertInvalidQuery(restTemplate.getForEntity("/api/v1/search?q=", String.class));
    }

    @Test
    @DisplayName("q over 200 chars returns 400 INVALID_QUERY")
    void invalidQuery_qTooLong() {
        String tooLong = "a".repeat(201);
        assertInvalidQuery(
                restTemplate.getForEntity("/api/v1/search?q=" + tooLong, String.class));
    }

    @Test
    @DisplayName("invalid type value returns 400 INVALID_QUERY")
    void invalidQuery_badType() {
        assertInvalidQuery(
                restTemplate.getForEntity("/api/v1/search?q=anything&type=audio", String.class));
    }

    @Test
    @DisplayName("invalid sort value returns 400 INVALID_QUERY")
    void invalidQuery_badSort() {
        assertInvalidQuery(
                restTemplate.getForEntity("/api/v1/search?q=anything&sort=alphabetical", String.class));
    }

    @Test
    @DisplayName("page=0 returns 400 INVALID_QUERY")
    void invalidQuery_pageBelowMin() {
        assertInvalidQuery(
                restTemplate.getForEntity("/api/v1/search?q=anything&page=0", String.class));
    }

    @Test
    @DisplayName("non-integer page returns 400 INVALID_QUERY")
    void invalidQuery_pageNotInteger() {
        assertInvalidQuery(
                restTemplate.getForEntity("/api/v1/search?q=anything&page=abc", String.class));
    }

    @Test
    @DisplayName("limit=0 returns 400 INVALID_QUERY")
    void invalidQuery_limitBelowMin() {
        assertInvalidQuery(
                restTemplate.getForEntity("/api/v1/search?q=anything&limit=0", String.class));
    }

    @Test
    @DisplayName("limit=101 returns 400 INVALID_QUERY")
    void invalidQuery_limitAboveMax() {
        assertInvalidQuery(
                restTemplate.getForEntity("/api/v1/search?q=anything&limit=101", String.class));
    }

    @Test
    @DisplayName("non-integer limit returns 400 INVALID_QUERY")
    void invalidQuery_limitNotInteger() {
        assertInvalidQuery(
                restTemplate.getForEntity("/api/v1/search?q=anything&limit=ten", String.class));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private void assertInvalidQuery(ResponseEntity<String> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        try {
            JsonNode root = objectMapper.readTree(response.getBody());
            assertThat(root.path("error").path("code").asText()).isEqualTo("INVALID_QUERY");
            assertThat(root.path("error").path("message").asText()).isNotBlank();
        } catch (Exception e) {
            throw new AssertionError("Response body is not valid JSON: " + response.getBody(), e);
        }
    }

    /**
     * Seeds three rows that share the keyword {@code sortable} but have distinct
     * {@code (finalScore, popularityScore)} so each {@code sort=…} value can be
     * verified independently.
     */
    private void seedSortFixture() {
        // alpha: finalScore=100, popularityScore=10
        seed("sf-alpha", "sortable alpha", null, ContentType.VIDEO, 100.0, 10.0);
        // beta:  finalScore=50,  popularityScore=20
        seed("sf-beta",  "sortable beta",  null, ContentType.VIDEO, 50.0,  20.0);
        // gamma: finalScore=25,  popularityScore=30
        seed("sf-gamma", "sortable gamma", null, ContentType.VIDEO, 25.0,  30.0);
    }

    /**
     * Persists a single content row with sensible defaults for every metric.
     * The {@link ContentRepository#upsert(Content)} path is used so the test
     * exercises the same SQL that production code does.
     */
    private void seed(
            String externalId,
            String title,
            String description,
            ContentType type,
            double finalScore,
            double popularityScore
    ) {
        Content content = new Content(
                UUID.randomUUID(),
                "test-provider",
                externalId,
                title,
                description,
                type,
                100L,                                              // views
                10L,                                               // likes
                5,                                                 // readingTime
                2L,                                                // reactions
                type == ContentType.VIDEO ? "PT5M" : null,         // duration
                List.of("tag"),                                    // tags
                Instant.now().minus(1, ChronoUnit.DAYS),            // publishedAt
                finalScore,
                popularityScore,
                0.0,                                               // relevanceScore (computed at query time)
                null,                                              // createdAt — DB-managed
                null                                               // updatedAt — DB-managed
        );
        repository.upsert(content);
    }
}
