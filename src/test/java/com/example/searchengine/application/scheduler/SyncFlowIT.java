package com.example.searchengine.application.scheduler;

import com.example.searchengine.application.ingest.ContentAggregator;
import com.example.searchengine.application.search.SearchQuery;
import com.example.searchengine.application.search.SearchResult;
import com.example.searchengine.application.search.SearchService;
import com.example.searchengine.infrastructure.cache.SearchCacheKeyGenerator;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

/**
 * End-to-end integration test for the synchronization flow:
 * scheduler → aggregator → repository → cache eviction.
 *
 * <p>Boots the full Spring context with {@code @SpringBootTest}, a PostgreSQL 16
 * Testcontainer for persistence, and two embedded WireMock servers stubbing the
 * JSON and XML providers with deterministic payloads. The cache is provided by an
 * in-memory {@link ConcurrentMapCacheManager} so cache eviction can be observed
 * without a Redis container.</p>
 *
 * <p>The scheduler is disabled via {@code aggregator.sync.enabled=false}; the test
 * drives the flow by invoking {@link ContentAggregator#runSync()} directly so it
 * does not depend on scheduler timing.</p>
 *
 * <p><b>Validates: Requirements 5.1, 5.2, 11.2, 11.3, 11.4, 12.4, 23.3</b></p>
 *
 * <p>Scenarios:
 * <ol>
 *   <li>(a) After {@code runSync()}, the {@code contents} table has the expected
 *       rows from both providers (REQ 5.1, 11.2).</li>
 *   <li>(b) Each row carries a {@code final_score} computed by the scoring engine
 *       (REQ 6, 7).</li>
 *   <li>(c) Priming the {@code search} cache and then running a sync evicts every
 *       cached entry (REQ 11.4, 12.4).</li>
 *   <li>(d) When one provider returns HTTP 500, items from the healthy provider
 *       are still upserted (REQ 11.3).</li>
 *   <li>(e) Running {@code runSync()} twice with identical WireMock payloads
 *       leaves the row count unchanged (REQ 5.2).</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@EnableAutoConfiguration(exclude = {
        RedisAutoConfiguration.class,
        RedisRepositoriesAutoConfiguration.class
})
@Testcontainers
@Import(SyncFlowIT.TestCacheConfig.class)
class SyncFlowIT {

    private static final String JSON_PATH = "/api/content";
    private static final String XML_PATH = "/feed";

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16")
                    .withDatabaseName("searchengine_syncflow")
                    .withUsername("test")
                    .withPassword("test");

    private static final WireMockServer JSON_WM =
            new WireMockServer(options().dynamicPort());
    private static final WireMockServer XML_WM =
            new WireMockServer(options().dynamicPort());

    @BeforeAll
    static void startWireMock() {
        JSON_WM.start();
        XML_WM.start();
    }

    @AfterAll
    static void stopWireMock() {
        JSON_WM.stop();
        XML_WM.stop();
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        // Persistence: live Postgres container with Flyway-managed schema (REQ 5.1).
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        // Provider URLs point at the per-test WireMock servers; the providers'
        // RestClient base URL maps directly to the path WireMock stubs against.
        registry.add("providers.json.base-url",
                () -> JSON_WM.baseUrl() + JSON_PATH);
        registry.add("providers.xml.base-url",
                () -> XML_WM.baseUrl() + XML_PATH);

        // Disable the actual scheduler — the test triggers runSync() directly
        // so there is no scheduler timing flakiness (REQ 11.5).
        registry.add("aggregator.sync.enabled", () -> "false");

        // Disable the production cache config so the in-memory test
        // CacheManager (registered as @Primary) is the only @Cacheable target.
        registry.add("cache.search.enabled", () -> "false");

        // Rate limiting is irrelevant to this flow.
        registry.add("ratelimit.enabled", () -> "false");
    }

    /**
     * Provides a {@link ConcurrentMapCacheManager} as the primary cache manager for
     * the test so that {@code @Cacheable} actually persists entries and
     * {@code @CacheEvict} can observably clear them. Marked {@code @Primary} so it
     * wins over {@code NoOpCacheManager} that the production config exposes when
     * {@code cache.search.enabled=false}.
     */
    @TestConfiguration
    static class TestCacheConfig {

        @Bean
        @Primary
        public CacheManager testCacheManager() {
            // The cache region must match the name used by @Cacheable / @CacheEvict
            // on SearchService and ContentAggregator (REQ 12.1, REQ 11.4).
            return new ConcurrentMapCacheManager("search");
        }
    }

    @Autowired
    private ContentAggregator contentAggregator;

    @Autowired
    private SearchService searchService;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private SearchCacheKeyGenerator cacheKeyGenerator;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetWorld() {
        // Wipe state between scenarios so each test sees a known starting point.
        jdbcTemplate.execute("DELETE FROM contents");
        Cache search = cacheManager.getCache("search");
        if (search != null) {
            search.clear();
        }
        WireMock.configureFor("localhost", JSON_WM.port());
        JSON_WM.resetAll();
        WireMock.configureFor("localhost", XML_WM.port());
        XML_WM.resetAll();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // (a) + (b) End-to-end happy path: rows upserted with computed final_score
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("runSync upserts rows from both providers with computed final_score")
    void runSync_persistsRowsFromBothProvidersWithComputedScore() {
        stubJsonProvider(stubJsonPayload());
        stubXmlProvider(stubXmlPayload());

        contentAggregator.runSync();

        // Row count: 1 video from the JSON provider + 2 items (article + video)
        // from the XML provider = 3 rows. (REQ 5.1, 11.2)
        long total = countRows();
        assertThat(total).isEqualTo(3L);

        // Provider attribution is preserved.
        assertThat(countByProvider("provider1-json")).isEqualTo(1L);
        assertThat(countByProvider("provider2-xml")).isEqualTo(2L);

        // Every row carries a final_score computed by the scoring engine.
        // The exact value depends on freshness (which is time-relative), but
        // the JSON video with views=15000, likes=1200 has baseScore=27 and
        // typeMultiplier=1.5, so finalScore is at least 27 * 1.5 = 40.5 even
        // with freshness=0 (REQ 6, 7).
        Double jsonScore = jdbcTemplate.queryForObject(
                "SELECT final_score FROM contents WHERE provider = ? AND external_id = ?",
                Double.class, "provider1-json", "json-1");
        assertThat(jsonScore).isNotNull();
        assertThat(jsonScore).isGreaterThanOrEqualTo(40.5);

        Double xmlVideoScore = jdbcTemplate.queryForObject(
                "SELECT final_score FROM contents WHERE provider = ? AND external_id = ?",
                Double.class, "provider2-xml", "xml-2");
        assertThat(xmlVideoScore).isNotNull();
        // XML video: views=2000, likes=200 → base=2 + 2 = 4, *1.5 = 6 plus eng/fresh
        assertThat(xmlVideoScore).isGreaterThanOrEqualTo(6.0);

        Double xmlArticleScore = jdbcTemplate.queryForObject(
                "SELECT final_score FROM contents WHERE provider = ? AND external_id = ?",
                Double.class, "provider2-xml", "xml-1");
        assertThat(xmlArticleScore).isNotNull();
        // XML article: readingTime=10, reactions=200 → base=10 + 4 = 14, *1.0 = 14
        assertThat(xmlArticleScore).isGreaterThanOrEqualTo(14.0);

        // The article→TEXT type rewrite is exercised via the XML adapter.
        String xmlArticleType = jdbcTemplate.queryForObject(
                "SELECT type FROM contents WHERE provider = ? AND external_id = ?",
                String.class, "provider2-xml", "xml-1");
        assertThat(xmlArticleType).isEqualTo("TEXT");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // (c) Cache eviction after a sync run (REQ 11.4, 12.4)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("runSync evicts every entry in the search cache region")
    void runSync_evictsSearchCacheRegion() {
        stubJsonProvider(stubJsonPayload());
        stubXmlProvider(stubXmlPayload());

        Cache search = cacheManager.getCache("search");
        assertThat(search).as("search cache region must be present").isNotNull();

        // Prime the cache directly so this test exercises only the eviction
        // contract (not the full search-query path). The cache key follows the
        // same shape that the production cache key generator emits, so a real
        // @Cacheable lookup would land on this entry.
        SearchQuery query = new SearchQuery("title", null, "score", 1, 10);
        Object key = cacheKeyGenerator.generate(searchService, null, query);
        SearchResult primed = new SearchResult(List.of(), 0L, 1, 10);
        search.put(key, primed);

        assertThat(search.get(key))
                .as("the priming write must populate the cache")
                .isNotNull();

        // Trigger a sync — @CacheEvict(cacheNames="search", allEntries=true)
        // on runSync() must clear every entry (REQ 11.4, 12.4).
        contentAggregator.runSync();

        assertThat(search.get(key))
                .as("the search cache must be empty after a sync run (REQ 12.4)")
                .isNull();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // (d) Provider failure isolation (REQ 11.3)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a 500 from one provider does not block items from the other")
    void runSync_isolatesProviderFailures() {
        // JSON provider returns 500 on every attempt — the adapter retries 3
        // times then returns []. The XML provider serves a valid payload and
        // its items must still land in the database (REQ 11.3).
        WireMock.configureFor("localhost", JSON_WM.port());
        WireMock.stubFor(get(urlPathEqualTo(JSON_PATH))
                .willReturn(aResponse().withStatus(500).withBody("upstream broken")));

        stubXmlProvider(stubXmlPayload());

        contentAggregator.runSync();

        // No rows from the failing provider.
        assertThat(countByProvider("provider1-json")).isZero();

        // All rows from the healthy provider were upserted.
        assertThat(countByProvider("provider2-xml")).isEqualTo(2L);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // (e) Idempotence: a second sync over the same payload does not duplicate (REQ 5.2)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("running runSync twice with the same payloads keeps the row count stable")
    void runSync_isIdempotentOnRepeat() {
        stubJsonProvider(stubJsonPayload());
        stubXmlProvider(stubXmlPayload());

        contentAggregator.runSync();
        long after_first = countRows();
        assertThat(after_first).isEqualTo(3L);

        contentAggregator.runSync();
        long after_second = countRows();
        assertThat(after_second).isEqualTo(3L);

        // Per-key uniqueness: the (provider, external_id) constraint guarantees
        // each external id maps to exactly one row no matter how many sync runs
        // have occurred (REQ 5.1, 5.2).
        Long jsonRowCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM contents WHERE provider = ? AND external_id = ?",
                Long.class, "provider1-json", "json-1");
        assertThat(jsonRowCount).isEqualTo(1L);
        Long xmlArticleCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM contents WHERE provider = ? AND external_id = ?",
                Long.class, "provider2-xml", "xml-1");
        assertThat(xmlArticleCount).isEqualTo(1L);
        Long xmlVideoCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM contents WHERE provider = ? AND external_id = ?",
                Long.class, "provider2-xml", "xml-2");
        assertThat(xmlVideoCount).isEqualTo(1L);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private long countRows() {
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM contents", Long.class);
        return count == null ? 0L : count;
    }

    private long countByProvider(String provider) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM contents WHERE provider = ?", Long.class, provider);
        return count == null ? 0L : count;
    }

    private void stubJsonProvider(String body) {
        WireMock.configureFor("localhost", JSON_WM.port());
        WireMock.stubFor(get(urlPathEqualTo(JSON_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body)));
    }

    private void stubXmlProvider(String body) {
        WireMock.configureFor("localhost", XML_WM.port());
        WireMock.stubFor(get(urlPathEqualTo(XML_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/xml")
                        .withBody(body)));
    }

    /** Returns a deterministic JSON payload with a single video item. */
    private String stubJsonPayload() {
        return """
                {
                  "contents": [
                    {
                      "id": "json-1",
                      "title": "Java Spring Tutorial title",
                      "type": "video",
                      "metrics": { "views": 15000, "likes": 1200, "duration": "PT10M" },
                      "published_at": "2024-06-15T10:00:00Z",
                      "tags": ["java", "spring"]
                    }
                  ]
                }
                """;
    }

    /** Returns a deterministic XML payload with one article and one video. */
    private String stubXmlPayload() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <feed>
                  <items>
                    <item>
                      <id>xml-1</id>
                      <headline>XML Article title</headline>
                      <type>article</type>
                      <stats>
                        <views>5000</views>
                        <likes>300</likes>
                        <reading_time>10</reading_time>
                        <reactions>200</reactions>
                      </stats>
                      <publication_date>2024-06-10T08:00:00Z</publication_date>
                      <categories>
                        <category>backend</category>
                      </categories>
                    </item>
                    <item>
                      <id>xml-2</id>
                      <headline>XML Video title</headline>
                      <type>video</type>
                      <stats>
                        <views>2000</views>
                        <likes>200</likes>
                        <reading_time>0</reading_time>
                        <reactions>0</reactions>
                      </stats>
                      <publication_date>2024-06-01T08:00:00Z</publication_date>
                      <categories>
                        <category>tutorial</category>
                      </categories>
                    </item>
                  </items>
                </feed>
                """;
    }
}
