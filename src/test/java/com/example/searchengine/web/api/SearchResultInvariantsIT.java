package com.example.searchengine.web.api;

import com.example.searchengine.SearchengineApplication;
import com.example.searchengine.domain.content.Content;
import com.example.searchengine.domain.content.ContentRepository;
import com.example.searchengine.domain.content.ContentType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Testcontainers integration property test for Search API result invariants.
 *
 * <p><b>Validates: Requirements 9.3, 9.4, 9.5, 9.6, 9.7, 9.8, 9.10, 9.11</b></p>
 *
 * <p>Feature: search-engine-service, Property 8: Search result invariants</p>
 *
 * <p>For any valid {@code SearchRequest} against a deterministic seeded corpus, the
 * response satisfies all of the following: (a) every item in {@code data} has
 * {@code type} equal to the requested {@code type} filter when one is supplied;
 * (b) {@code data} is non-increasing in the chosen sort key
 * ({@code final_score}, {@code popularity_score}, or {@code ts_rank}) with deterministic
 * tie-break by {@code id} ascending; (c) {@code data.length ≤ limit};
 * (d) {@code pagination.page == requested page} and {@code pagination.limit == requested limit};
 * (e) {@code pagination.total ≥ len(data)} and is a non-negative integer.
 * For invalid query parameters, the API returns 400 INVALID_QUERY.</p>
 *
 * <p>The test bootstraps the full Spring Boot application against a Testcontainers
 * PostgreSQL 16 instance on a random port. jqwik's {@code @BeforeContainer} is the
 * appropriate hook because the property engine does not honor Spring's
 * {@code @SpringBootTest} lifecycle directly.</p>
 */
public class SearchResultInvariantsIT {

    /** Vocabulary used in seeded titles/descriptions and in {@code q} generation. */
    private static final List<String> VOCABULARY = List.of(
            "spring", "java", "kotlin", "tutorial", "guide",
            "framework", "database", "container", "cloud", "system"
    );

    private static final long FIXTURE_SEED = 0xDEADBEEFL;
    private static final int FIXTURE_SIZE = 50;

    private static PostgreSQLContainer<?> postgres;
    private static ConfigurableApplicationContext context;
    private static int port;
    private static HttpClient http;
    private static ObjectMapper objectMapper;
    private static JdbcTemplate jdbcTemplate;

    // ──────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────────────────────────────────

    @BeforeContainer
    static void bootstrap() {
        postgres = new PostgreSQLContainer<>("postgres:16")
                .withDatabaseName("searchengine_it")
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
        properties.put("server.port", "0"); // RANDOM_PORT
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
        port = ((WebServerApplicationContext) context).getWebServer().getPort();

        http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        objectMapper = new ObjectMapper();
        jdbcTemplate = new JdbcTemplate(context.getBean(javax.sql.DataSource.class));

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

    private static void seedFixture() {
        ContentRepository repository = context.getBean(ContentRepository.class);
        Random rng = new Random(FIXTURE_SEED);
        Instant now = Instant.parse("2024-06-15T12:00:00Z");

        // 50 mixed-type rows with deterministic varying scores and dates.
        for (int i = 0; i < FIXTURE_SIZE; i++) {
            ContentType type = (i % 2 == 0) ? ContentType.VIDEO : ContentType.TEXT;
            String w1 = VOCABULARY.get(rng.nextInt(VOCABULARY.size()));
            String w2 = VOCABULARY.get(rng.nextInt(VOCABULARY.size()));
            String title = w1 + " " + w2 + " " + (i + 1);
            String description = "About " + w1 + " and " + w2;
            int daysOld = rng.nextInt(120);
            // Use small score range with deliberate ties so the id-ASC tie-break
            // is exercised by the property.
            double finalScore = (i % 13);
            double popularityScore = (i % 11);

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

    // ──────────────────────────────────────────────────────────────────────────
    // Generators
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Generator for {@code (q, type, sort, page, limit)} tuples that always pass
     * Bean Validation (REQ 8.3, 8.4, 9.2, 9.7, 9.8, 9.9). Using fixture vocabulary
     * for {@code q} keeps non-empty result sets common, but empty results still
     * satisfy every invariant.
     */
    @Provide
    Arbitrary<ValidQueryTuple> validTuples() {
        Arbitrary<String> q = Arbitraries.of(VOCABULARY);
        Arbitrary<String> type = Arbitraries.of((String) null, "video", "text");
        Arbitrary<String> sort = Arbitraries.of((String) null, "score", "popularity", "relevance");
        Arbitrary<Integer> page = Arbitraries.integers().between(1, 5);
        Arbitrary<Integer> limit = Arbitraries.integers().between(1, 20);
        return Combinators.combine(q, type, sort, page, limit).as(ValidQueryTuple::new);
    }

    /**
     * Generator producing tuples that violate at least one constraint declared in
     * Requirements 8 and 9. One {@link InvalidKind} per iteration ensures every
     * branch of the validation pipeline is sampled.
     */
    @Provide
    Arbitrary<InvalidQueryTuple> invalidTuples() {
        return Arbitraries.of(InvalidKind.values()).flatMap(kind -> switch (kind) {
            case BLANK_Q -> Arbitraries.just(
                    new InvalidQueryTuple("", null, null, null, null));
            case TOO_LONG_Q -> Arbitraries.strings()
                    .alpha().ofMinLength(201).ofMaxLength(220)
                    .map(s -> new InvalidQueryTuple(s, null, null, null, null));
            case INVALID_TYPE -> Arbitraries.of("audio", "image", "podcast", "garbage")
                    .map(t -> new InvalidQueryTuple("java", t, null, null, null));
            case INVALID_SORT -> Arbitraries.of("rank", "popular", "newest", "garbage")
                    .map(s -> new InvalidQueryTuple("java", null, s, null, null));
            case INVALID_PAGE -> Arbitraries.of("0", "-1", "abc")
                    .map(p -> new InvalidQueryTuple("java", null, null, p, null));
            case INVALID_LIMIT -> Arbitraries.of("0", "-5", "101", "1000", "abc")
                    .map(l -> new InvalidQueryTuple("java", null, null, null, l));
        });
    }

    enum InvalidKind {
        BLANK_Q, TOO_LONG_Q, INVALID_TYPE, INVALID_SORT, INVALID_PAGE, INVALID_LIMIT
    }

    record ValidQueryTuple(String q, String type, String sort, Integer page, Integer limit) {
    }

    record InvalidQueryTuple(String q, String type, String sort, String page, String limit) {
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Properties
    // ──────────────────────────────────────────────────────────────────────────

    @Property(tries = 100)
    @Label("Feature: search-engine-service, Property 8: Search result invariants")
    void searchResultInvariantsHold(@ForAll("validTuples") ValidQueryTuple tuple) throws Exception {
        String url = buildValidUrl(tuple);
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new AssertionError(
                    "Expected 200 for valid query but got " + response.statusCode()
                            + " url=" + url + " body=" + response.body());
        }

        JsonNode body = objectMapper.readTree(response.body());
        JsonNode data = body.get("data");
        JsonNode pagination = body.get("pagination");

        if (data == null || !data.isArray()) {
            throw new AssertionError("response.data must be a JSON array; body=" + response.body());
        }
        if (pagination == null || !pagination.isObject()) {
            throw new AssertionError("response.pagination must be a JSON object; body=" + response.body());
        }

        int requestedPage = tuple.page();
        int requestedLimit = tuple.limit();

        // (d) pagination.page == requested page (REQ 9.7, 9.10)
        int actualPage = pagination.get("page").asInt();
        if (actualPage != requestedPage) {
            throw new AssertionError("pagination.page mismatch: expected="
                    + requestedPage + " actual=" + actualPage + " url=" + url);
        }

        // (d) pagination.limit == requested limit (REQ 9.8, 9.10)
        int actualLimit = pagination.get("limit").asInt();
        if (actualLimit != requestedLimit) {
            throw new AssertionError("pagination.limit mismatch: expected="
                    + requestedLimit + " actual=" + actualLimit + " url=" + url);
        }

        // (e) pagination.total is a non-negative integer (REQ 9.10, 9.11)
        long total = pagination.get("total").asLong();
        if (total < 0) {
            throw new AssertionError("pagination.total must be >= 0 but was " + total + " url=" + url);
        }

        // (c) len(data) <= limit (REQ 9.8, 20.3)
        if (data.size() > requestedLimit) {
            throw new AssertionError("data.length=" + data.size()
                    + " exceeds requested limit=" + requestedLimit + " url=" + url);
        }

        // pagination.total >= len(data) (REQ 9.10, 9.11)
        if (total < data.size()) {
            throw new AssertionError("pagination.total=" + total
                    + " < data.length=" + data.size() + " url=" + url);
        }

        // (a) type filter respected (REQ 9.1)
        String typeFilter = tuple.type();
        if (typeFilter != null) {
            for (JsonNode item : data) {
                String itemType = item.get("type").asText();
                if (!typeFilter.equals(itemType)) {
                    throw new AssertionError("type filter violated: requested="
                            + typeFilter + " item.type=" + itemType + " url=" + url);
                }
            }
        }

        // (b) Items are non-increasing in the chosen sort key with id-ASC tie-break.
        // (REQ 9.3, 9.4, 9.5, 9.6)
        if (data.size() >= 2) {
            String sortField = tuple.sort() == null ? "score" : tuple.sort();
            verifySortOrder(data, sortField, tuple.q(), url);
        }
    }

    @Property(tries = 100)
    @Label("Feature: search-engine-service, Property 8: Search result invariants — invalid query parameters")
    void invalidQueriesReturn400(@ForAll("invalidTuples") InvalidQueryTuple tuple) throws Exception {
        String url = buildInvalidUrl(tuple);
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 400) {
            throw new AssertionError("Expected 400 for invalid query but got "
                    + response.statusCode() + " url=" + url + " body=" + response.body());
        }

        JsonNode body = objectMapper.readTree(response.body());
        JsonNode error = body.get("error");
        if (error == null) {
            throw new AssertionError("error envelope missing for url=" + url + " body=" + response.body());
        }
        String code = error.path("code").asText();
        if (!"INVALID_QUERY".equals(code)) {
            throw new AssertionError("error.code expected INVALID_QUERY but got "
                    + code + " url=" + url + " body=" + response.body());
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Verifies the items in {@code data} are non-increasing under the chosen sort
     * key, with deterministic tie-break by {@code id} ascending. The summary DTO
     * exposes only {@code score} (= {@code final_score}); for {@code popularity}
     * and {@code relevance} the actual sort key is fetched from the database
     * using the same expressions the service issues.
     */
    private static void verifySortOrder(JsonNode data, String sortField, String q, String url) {
        List<UUID> ids = new ArrayList<>();
        for (JsonNode item : data) {
            ids.add(UUID.fromString(item.get("id").asText()));
        }

        List<Double> sortValues = switch (sortField) {
            case "score" -> {
                List<Double> values = new ArrayList<>(data.size());
                for (JsonNode item : data) {
                    values.add(item.get("score").asDouble());
                }
                yield values;
            }
            case "popularity" -> fetchPopularityScores(ids);
            case "relevance" -> fetchRelevanceRanks(ids, q);
            default -> throw new AssertionError("unexpected sort: " + sortField);
        };

        for (int i = 1; i < sortValues.size(); i++) {
            double prev = sortValues.get(i - 1);
            double curr = sortValues.get(i);
            if (curr > prev) {
                throw new AssertionError("sort=" + sortField
                        + " not non-increasing: prev=" + prev + " curr=" + curr
                        + " at idx " + i + " url=" + url);
            }
            if (Double.compare(prev, curr) == 0) {
                UUID prevId = ids.get(i - 1);
                UUID currId = ids.get(i);
                if (prevId.compareTo(currId) > 0) {
                    throw new AssertionError("id tie-break violated for sort="
                            + sortField + " at value " + prev
                            + ": prev=" + prevId + " curr=" + currId + " url=" + url);
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

    private static String buildValidUrl(ValidQueryTuple tuple) {
        StringBuilder sb = new StringBuilder("http://localhost:")
                .append(port).append("/api/v1/search?q=")
                .append(URLEncoder.encode(tuple.q(), StandardCharsets.UTF_8));
        if (tuple.type() != null) {
            sb.append("&type=").append(tuple.type());
        }
        if (tuple.sort() != null) {
            sb.append("&sort=").append(tuple.sort());
        }
        sb.append("&page=").append(tuple.page());
        sb.append("&limit=").append(tuple.limit());
        return sb.toString();
    }

    private static String buildInvalidUrl(InvalidQueryTuple tuple) {
        StringBuilder sb = new StringBuilder("http://localhost:")
                .append(port).append("/api/v1/search?q=")
                .append(URLEncoder.encode(tuple.q() == null ? "" : tuple.q(), StandardCharsets.UTF_8));
        if (tuple.type() != null) {
            sb.append("&type=").append(URLEncoder.encode(tuple.type(), StandardCharsets.UTF_8));
        }
        if (tuple.sort() != null) {
            sb.append("&sort=").append(URLEncoder.encode(tuple.sort(), StandardCharsets.UTF_8));
        }
        if (tuple.page() != null) {
            sb.append("&page=").append(URLEncoder.encode(tuple.page(), StandardCharsets.UTF_8));
        }
        if (tuple.limit() != null) {
            sb.append("&limit=").append(URLEncoder.encode(tuple.limit(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }
}
