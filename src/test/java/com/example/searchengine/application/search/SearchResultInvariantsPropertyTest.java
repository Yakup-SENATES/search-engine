package com.example.searchengine.application.search;

import com.example.searchengine.SearchengineApplication;
import com.example.searchengine.domain.content.Content;
import com.example.searchengine.domain.content.ContentRepository;
import com.example.searchengine.domain.content.ContentType;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AfterContainer;
import net.jqwik.api.lifecycle.BeforeContainer;

import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Property-based test for search result invariants.
 *
 * <p><b>Validates: Requirements 8.2, 9.1, 9.3, 9.10, 9.11, 23.3</b></p>
 *
 * <p>Feature: search-engine-service, Property 8: Search result invariants</p>
 *
 * <p>For any valid {@link SearchQuery} (q, type, sort, page, limit) executed against
 * a deterministic seeded fixture of {@code Content} rows, {@link SearchService#search}
 * returns a {@link SearchResult} whose invariants always hold:</p>
 * <ul>
 *   <li>(a) {@code data.size() <= limit} (pagination cap respected, REQ 9.8).</li>
 *   <li>(b) {@code pagination.page == query.page}, {@code pagination.limit == query.limit},
 *       {@code pagination.total >= data.size()}, {@code pagination.total >= 0} (REQ 9.10).</li>
 *   <li>(c) When {@code type} is set, every returned item has {@code type == query.type}
 *       (REQ 9.1).</li>
 *   <li>(d) When {@code sort=score}, results are sorted by {@code finalScore DESC} with
 *       {@code id ASC} tie-break (REQ 9.3, 10.3).</li>
 *   <li>(e) When {@code data} is empty, {@code pagination.total == 0} (REQ 9.11).</li>
 * </ul>
 *
 * <p>The test bootstraps the Spring Boot application against a Testcontainers
 * PostgreSQL 16 instance (REQ 23.3) and seeds ~50 deterministic {@code Content}
 * rows mixing {@code VIDEO} and {@code TEXT} with varying {@code finalScore} and
 * deliberate ties so the {@code id ASC} tie-break is exercised. jqwik's
 * {@link BeforeContainer @BeforeContainer} is used because the property engine
 * does not honor Spring's {@code @SpringBootTest} lifecycle directly.</p>
 */
public class SearchResultInvariantsPropertyTest {

    /** Vocabulary used in seeded titles/descriptions and in {@code q} generation. */
    private static final List<String> VOCABULARY = List.of(
            "alpha", "beta", "gamma", "delta", "epsilon",
            "zeta", "eta", "theta", "iota", "kappa"
    );

    private static final long FIXTURE_SEED = 0xC0FFEEL;
    private static final int FIXTURE_SIZE = 50;

    private static PostgreSQLContainer<?> postgres;
    private static ConfigurableApplicationContext context;
    private static SearchService searchService;
    private static JdbcTemplate jdbcTemplate;

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    @BeforeContainer
    static void bootstrap() {
        postgres = new PostgreSQLContainer<>("postgres:16")
                .withDatabaseName("searchengine_pbt")
                .withUsername("test")
                .withPassword("test");
        postgres.start();

        SpringApplication app = new SpringApplication(SearchengineApplication.class);
        Map<String, Object> properties = new HashMap<>();
        properties.put("spring.datasource.url", postgres.getJdbcUrl());
        properties.put("spring.datasource.username", postgres.getUsername());
        properties.put("spring.datasource.password", postgres.getPassword());
        properties.put("spring.flyway.enabled", "true");
        properties.put("spring.jpa.hibernate.ddl-auto", "validate");
        properties.put("server.port", "0");
        // Provider HTTP clients are never invoked in this test (sync disabled).
        properties.put("providers.json.base-url", "http://localhost:65535/json-disabled");
        properties.put("providers.xml.base-url", "http://localhost:65535/xml-disabled");
        properties.put("aggregator.sync.enabled", "false");
        properties.put("cache.search.enabled", "false");
        properties.put("ratelimit.enabled", "false");
        // Avoid the auto-configured Redis connection factory attempting any work.
        properties.put("spring.autoconfigure.exclude", String.join(",",
                "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration",
                "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"));
        app.setDefaultProperties(properties);

        context = app.run();
        searchService = context.getBean(SearchService.class);
        jdbcTemplate = new JdbcTemplate(context.getBean(DataSource.class));

        seedFixture();
    }

    @AfterContainer
    static void shutdown() {
        if (context != null) {
            context.close();
        }
        if (postgres != null) {
            postgres.stop();
        }
    }

    /**
     * Seeds a deterministic fixture of {@code FIXTURE_SIZE} {@link Content} rows
     * with a mix of {@code VIDEO}/{@code TEXT}, varying ages, and a small
     * {@code finalScore} range so ties on the score column are common (forcing
     * the {@code id ASC} tie-break to be exercised by the property).
     */
    private static void seedFixture() {
        ContentRepository repository = context.getBean(ContentRepository.class);
        Random rng = new Random(FIXTURE_SEED);
        Instant now = Instant.parse("2024-06-15T12:00:00Z");

        for (int i = 0; i < FIXTURE_SIZE; i++) {
            ContentType type = (i % 2 == 0) ? ContentType.VIDEO : ContentType.TEXT;
            String w1 = VOCABULARY.get(rng.nextInt(VOCABULARY.size()));
            String w2 = VOCABULARY.get(rng.nextInt(VOCABULARY.size()));
            String title = w1 + " " + w2 + " row " + (i + 1);
            String description = "About " + w1 + " and " + w2;
            int daysOld = rng.nextInt(120);
            // Modest range with deliberate collisions on i % 7 to exercise ties.
            double finalScore = i % 7;
            double popularityScore = i % 5;

            Content content = new Content(
                    UUID.randomUUID(),
                    "fixture-provider",
                    "ext-" + i,
                    title,
                    description,
                    type,
                    1000L * (i + 1),
                    100L * (i + 1),
                    Math.max(1, i),
                    50L * (i + 1),
                    type == ContentType.VIDEO ? "PT5M" : null,
                    List.of(w1),
                    now.minus(daysOld, ChronoUnit.DAYS),
                    finalScore,
                    popularityScore,
                    0.0,
                    null,
                    null
            );
            repository.upsert(content);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Generators
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Generator for valid {@link SearchQuery} values whose vocabulary overlaps
     * the seeded fixture so non-empty result sets are common while empty results
     * still satisfy every invariant (REQ 9.11).
     */
    @Provide
    Arbitrary<SearchQuery> validQueries() {
        Arbitrary<String> q = Arbitraries.of(VOCABULARY);
        Arbitrary<String> type = Arbitraries.of((String) null, "video", "text");
        Arbitrary<String> sort = Arbitraries.of((String) null, "score", "popularity", "relevance");
        Arbitrary<Integer> page = Arbitraries.integers().between(1, 5);
        Arbitrary<Integer> limit = Arbitraries.integers().between(1, 20);
        return Combinators.combine(q, type, sort, page, limit).as(SearchQuery::new);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Property
    // ─────────────────────────────────────────────────────────────────────────

    @Property(tries = 100)
    @Label("Feature: search-engine-service, Property 8: Search result invariants")
    void searchResultInvariantsHold(@ForAll("validQueries") SearchQuery query) {
        SearchResult result = searchService.search(query);

        if (result == null) {
            throw new AssertionError("SearchResult must not be null for query=" + query);
        }
        List<Content> items = result.items();
        if (items == null) {
            throw new AssertionError("SearchResult.items must not be null for query=" + query);
        }

        // (a) data.size() <= limit (pagination cap respected — REQ 9.8)
        if (items.size() > query.limit()) {
            throw new AssertionError(
                    "data.size()=" + items.size()
                            + " exceeds requested limit=" + query.limit()
                            + " query=" + query);
        }

        // (b) pagination.page / limit / total invariants — REQ 9.10
        if (result.page() != query.page()) {
            throw new AssertionError(
                    "pagination.page mismatch: expected=" + query.page()
                            + " actual=" + result.page() + " query=" + query);
        }
        if (result.limit() != query.limit()) {
            throw new AssertionError(
                    "pagination.limit mismatch: expected=" + query.limit()
                            + " actual=" + result.limit() + " query=" + query);
        }
        if (result.total() < 0) {
            throw new AssertionError(
                    "pagination.total must be >= 0 but was " + result.total()
                            + " query=" + query);
        }
        if (result.total() < items.size()) {
            throw new AssertionError(
                    "pagination.total=" + result.total()
                            + " < data.size()=" + items.size()
                            + " query=" + query);
        }

        // (c) When type is set, every returned item has type == query.type — REQ 9.1
        String typeFilter = query.type();
        if (typeFilter != null && !typeFilter.isBlank()) {
            ContentType expected = ContentType.fromProviderValue(typeFilter);
            for (Content c : items) {
                if (c.type() != expected) {
                    throw new AssertionError(
                            "type filter violated: requested=" + expected
                                    + " item.type=" + c.type()
                                    + " id=" + c.id() + " query=" + query);
                }
            }
        }

        // (d) When sort=score (or sort is null/absent and defaults to score),
        // results are sorted by finalScore DESC with id ASC tie-break — REQ 9.3, 10.3
        String sort = query.sort();
        boolean isScoreSort = sort == null || sort.isBlank()
                || "score".equalsIgnoreCase(sort.trim());
        if (isScoreSort && items.size() >= 2) {
            for (int i = 1; i < items.size(); i++) {
                Content prev = items.get(i - 1);
                Content curr = items.get(i);
                if (curr.finalScore() > prev.finalScore()) {
                    throw new AssertionError(
                            "sort=score not non-increasing: prev.finalScore="
                                    + prev.finalScore() + " curr.finalScore=" + curr.finalScore()
                                    + " at idx " + i + " query=" + query);
                }
                if (Double.compare(prev.finalScore(), curr.finalScore()) == 0) {
                    if (prev.id().compareTo(curr.id()) > 0) {
                        throw new AssertionError(
                                "id tie-break violated for sort=score at finalScore="
                                        + prev.finalScore()
                                        + ": prev.id=" + prev.id()
                                        + " curr.id=" + curr.id()
                                        + " query=" + query);
                    }
                }
            }
        }

        // For non-score sorts, still verify monotonicity by fetching the actual
        // sort key from the database for each returned id, using the same
        // expressions the production query uses. This keeps the property
        // self-contained even when the summary DTO does not expose the key.
        if (!isScoreSort && items.size() >= 2) {
            verifyNonScoreSortOrder(items, sort, query);
        }

        // (e) When data is empty, pagination.total == 0 — REQ 9.11
        if (items.isEmpty() && result.total() != 0) {
            throw new AssertionError(
                    "empty data must imply pagination.total == 0 but was "
                            + result.total() + " query=" + query);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Verifies the items are non-increasing under {@code sort} (popularity or
     * relevance) with id-ASC tie-break by reading the actual sort value from
     * the database for each returned id. The expressions used here mirror those
     * in {@code ContentJpaRepository}.
     */
    private static void verifyNonScoreSortOrder(List<Content> items, String sort, SearchQuery query) {
        String normalized = sort.trim().toLowerCase();
        List<UUID> ids = new ArrayList<>(items.size());
        for (Content c : items) {
            ids.add(c.id());
        }

        List<Double> sortValues = switch (normalized) {
            case "popularity" -> fetchPopularityScores(ids);
            case "relevance" -> fetchRelevanceRanks(ids, query.q());
            default -> throw new AssertionError("unexpected sort: " + sort);
        };

        for (int i = 1; i < sortValues.size(); i++) {
            double prev = sortValues.get(i - 1);
            double curr = sortValues.get(i);
            if (curr > prev) {
                throw new AssertionError(
                        "sort=" + normalized + " not non-increasing: prev=" + prev
                                + " curr=" + curr + " at idx " + i + " query=" + query);
            }
            if (Double.compare(prev, curr) == 0) {
                UUID prevId = ids.get(i - 1);
                UUID currId = ids.get(i);
                if (prevId.compareTo(currId) > 0) {
                    throw new AssertionError(
                            "id tie-break violated for sort=" + normalized
                                    + " at value " + prev
                                    + ": prev=" + prevId + " curr=" + currId + " query=" + query);
                }
            }
        }
    }

    private static List<Double> fetchPopularityScores(List<UUID> ids) {
        List<Double> values = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            Double v = jdbcTemplate.queryForObject(
                    "SELECT popularity_score FROM contents WHERE id = ?",
                    Double.class,
                    id);
            values.add(v == null ? 0.0 : v);
        }
        return values;
    }

    private static List<Double> fetchRelevanceRanks(List<UUID> ids, String q) {
        List<Double> values = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            Double v = jdbcTemplate.queryForObject(
                    "SELECT ts_rank_cd("
                            + "to_tsvector('simple', title || ' ' || coalesce(description, '')), "
                            + "plainto_tsquery('simple', ?)) "
                            + "FROM contents WHERE id = ?",
                    Double.class,
                    q, id);
            values.add(v == null ? 0.0 : v);
        }
        return values;
    }
}
