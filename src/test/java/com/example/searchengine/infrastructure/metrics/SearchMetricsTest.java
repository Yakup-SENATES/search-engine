package com.example.searchengine.infrastructure.metrics;

import com.example.searchengine.application.search.DefaultSearchService;
import com.example.searchengine.application.search.SearchQuery;
import com.example.searchengine.application.search.SearchResult;
import com.example.searchengine.domain.content.Content;
import com.example.searchengine.domain.content.ContentRepository;
import com.example.searchengine.domain.content.ContentType;
import com.example.searchengine.domain.content.SearchCriteria;
import com.example.searchengine.domain.content.SearchPage;
import com.example.searchengine.infrastructure.cache.SearchCacheKeyGenerator;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.cache.support.NoOpCacheManager;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SearchMetrics} backed by a {@link SimpleMeterRegistry}
 * and exercised end-to-end through {@link DefaultSearchService} with a mock
 * {@link CacheManager}.
 *
 * <p>Validates Requirements 1.4 and 1.5 (operability quick-wins): the meter
 * names, the {@code cache_hit} tag schema, and counter increments on hit and
 * miss paths.</p>
 */
class SearchMetricsTest {

    private MeterRegistry registry;
    private SearchMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new SearchMetrics(registry);
    }

    // ─── SearchMetrics in isolation ──────────────────────────────────────────

    @Test
    @DisplayName("Constructor pre-registers all three meters with correct names + tags (REQ 1.4, 1.5)")
    void preRegistersAllMeters() {
        assertThat(registry.find(SearchMetrics.QUERY_DURATION_METER)
                .tag("cache_hit", "true").timer())
                .isNotNull();
        assertThat(registry.find(SearchMetrics.QUERY_DURATION_METER)
                .tag("cache_hit", "false").timer())
                .isNotNull();
        assertThat(registry.find(SearchMetrics.CACHE_HITS_METER).counter())
                .isNotNull();
        assertThat(registry.find(SearchMetrics.CACHE_MISSES_METER).counter())
                .isNotNull();
    }

    @Test
    @DisplayName("record(true) increments only the cache_hit=true timer (REQ 1.4)")
    void recordCacheHitTimerSelectsHitTag() {
        metrics.record(Duration.ofMillis(20), true);

        Timer hitTimer = registry.find(SearchMetrics.QUERY_DURATION_METER)
                .tag("cache_hit", "true").timer();
        Timer missTimer = registry.find(SearchMetrics.QUERY_DURATION_METER)
                .tag("cache_hit", "false").timer();
        assertThat(hitTimer.count()).isEqualTo(1L);
        assertThat(missTimer.count()).isZero();
    }

    @Test
    @DisplayName("record(false) increments only the cache_hit=false timer (REQ 1.4)")
    void recordCacheMissTimerSelectsMissTag() {
        metrics.record(Duration.ofMillis(70), false);

        Timer hitTimer = registry.find(SearchMetrics.QUERY_DURATION_METER)
                .tag("cache_hit", "true").timer();
        Timer missTimer = registry.find(SearchMetrics.QUERY_DURATION_METER)
                .tag("cache_hit", "false").timer();
        assertThat(hitTimer.count()).isZero();
        assertThat(missTimer.count()).isEqualTo(1L);
    }

    @Test
    @DisplayName("recordHit / recordMiss increment the standalone counters (REQ 1.5)")
    void recordHitAndMissUpdateCounters() {
        metrics.recordHit();
        metrics.recordHit();
        metrics.recordMiss();

        Counter hits = registry.find(SearchMetrics.CACHE_HITS_METER).counter();
        Counter misses = registry.find(SearchMetrics.CACHE_MISSES_METER).counter();
        assertThat(hits.count()).isEqualTo(2.0);
        assertThat(misses.count()).isEqualTo(1.0);
    }

    // ─── Wired through DefaultSearchService ──────────────────────────────────

    @Test
    @DisplayName("Search miss path: increments misses counter + cache_hit=false timer + populates cache (REQ 1.4, 1.5)")
    void searchServiceMissPathIncrementsMissCounterAndTimer() {
        ContentRepository repository = mock(ContentRepository.class);
        when(repository.search(any(SearchCriteria.class)))
                .thenReturn(new SearchPage(List.of(sampleContent("ext-1")), 1L));

        ConcurrentMapCacheManager cacheManager = new ConcurrentMapCacheManager("search");
        SearchCacheKeyGenerator keyGenerator = new SearchCacheKeyGenerator();
        DefaultSearchService service = new DefaultSearchService(repository, cacheManager, keyGenerator, metrics);

        SearchQuery query = new SearchQuery("docker", null, "score", 1, 10);
        SearchResult result = service.search(query);

        assertThat(result).isNotNull();
        assertThat(result.total()).isEqualTo(1L);
        verify(repository, times(1)).search(any(SearchCriteria.class));

        Counter misses = registry.find(SearchMetrics.CACHE_MISSES_METER).counter();
        Counter hits = registry.find(SearchMetrics.CACHE_HITS_METER).counter();
        Timer missTimer = registry.find(SearchMetrics.QUERY_DURATION_METER)
                .tag("cache_hit", "false").timer();

        assertThat(misses.count()).isEqualTo(1.0);
        assertThat(hits.count()).isZero();
        assertThat(missTimer.count()).isEqualTo(1L);

        // Cache was populated for the next call.
        Object key = keyGenerator.generate(service, null, query);
        assertThat(cacheManager.getCache("search").get(key)).isNotNull();
    }

    @Test
    @DisplayName("Search hit path: increments hits counter + cache_hit=true timer + skips repository (REQ 1.4, 1.5)")
    void searchServiceHitPathIncrementsHitCounterAndTimer() {
        ContentRepository repository = mock(ContentRepository.class);
        when(repository.search(any(SearchCriteria.class)))
                .thenReturn(new SearchPage(List.of(sampleContent("ext-2")), 1L));

        ConcurrentMapCacheManager cacheManager = new ConcurrentMapCacheManager("search");
        SearchCacheKeyGenerator keyGenerator = new SearchCacheKeyGenerator();
        DefaultSearchService service = new DefaultSearchService(repository, cacheManager, keyGenerator, metrics);

        SearchQuery query = new SearchQuery("kafka", "video", "popularity", 1, 5);
        // Prime the cache.
        service.search(query);
        // Second call: served from the cache.
        SearchResult result = service.search(query);

        assertThat(result).isNotNull();
        verify(repository, times(1)).search(any(SearchCriteria.class));

        Counter hits = registry.find(SearchMetrics.CACHE_HITS_METER).counter();
        Counter misses = registry.find(SearchMetrics.CACHE_MISSES_METER).counter();
        Timer hitTimer = registry.find(SearchMetrics.QUERY_DURATION_METER)
                .tag("cache_hit", "true").timer();

        assertThat(hits.count()).isEqualTo(1.0);
        assertThat(misses.count()).isEqualTo(1.0);
        assertThat(hitTimer.count()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Search with NoOpCacheManager always counts as miss (REQ 1.4, 1.5)")
    void searchWithNoOpCacheManagerAlwaysRecordsMiss() {
        ContentRepository repository = mock(ContentRepository.class);
        when(repository.search(any(SearchCriteria.class)))
                .thenReturn(new SearchPage(List.of(sampleContent("ext-3")), 1L));

        DefaultSearchService service = new DefaultSearchService(
                repository, new NoOpCacheManager(), new SearchCacheKeyGenerator(), metrics);

        SearchQuery query = new SearchQuery("postgres", null, null, 1, 10);
        service.search(query);
        service.search(query);

        // NoOpCacheManager.getCache("search") returns a NoOpCache whose get() always
        // returns null, so both calls take the miss branch.
        verify(repository, times(2)).search(any(SearchCriteria.class));
        Counter misses = registry.find(SearchMetrics.CACHE_MISSES_METER).counter();
        Counter hits = registry.find(SearchMetrics.CACHE_HITS_METER).counter();
        assertThat(misses.count()).isEqualTo(2.0);
        assertThat(hits.count()).isZero();
    }

    private static Content sampleContent(String externalId) {
        return new Content(
                UUID.randomUUID(),
                "test-provider",
                externalId,
                "Title " + externalId,
                "Description " + externalId,
                ContentType.VIDEO,
                100L, 10L, 0, 0L, "PT5M",
                List.of("tag"),
                Instant.parse("2024-06-01T00:00:00Z"),
                10.0, 5.0, 0.0,
                Instant.parse("2024-06-01T00:00:00Z"),
                Instant.parse("2024-06-01T00:00:00Z")
        );
    }
}
