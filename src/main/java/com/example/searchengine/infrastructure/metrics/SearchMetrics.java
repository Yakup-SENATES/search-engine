package com.example.searchengine.infrastructure.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Records the search query timer plus cache hit / miss counters that
 * back the operability quick-wins dashboards.
 *
 * <p>Meter taxonomy (also surfaced through {@code /actuator/prometheus}):
 * <ul>
 *   <li>{@value #QUERY_DURATION_METER} — Timer tagged
 *       {@code cache_hit=true|false}; one variant per tag value, both
 *       eagerly registered in the constructor so dashboards see them
 *       even before the first request lands.</li>
 *   <li>{@value #CACHE_HITS_METER} — Counter (no tags) bumped each time
 *       a search request is served from the cache.</li>
 *   <li>{@value #CACHE_MISSES_METER} — Counter (no tags) bumped each time
 *       a search request bypasses the cache.</li>
 * </ul>
 *
 * <p>Hit / miss is captured at the call site by inspecting the
 * {@code Cache.ValueWrapper} returned by
 * {@code com.example.searchengine.infrastructure.cache.ResilientCacheManager.get(...)}:
 * a non-null wrapper is a hit, anything else (null wrapper, no cache region,
 * or backend failure) is a miss.</p>
 *
 * <p>The user-supplied keyword and any other request data are
 * <strong>not</strong> exposed as tag values (REQ 1.8) — only the static,
 * boolean {@code cache_hit} dimension is tagged.</p>
 *
 * <p>Validates: Requirements 1.4, 1.5, 1.8 (operability quick-wins).</p>
 */
@Component
public class SearchMetrics {

    /** Name of the Timer that captures end-to-end search-request latency. */
    public static final String QUERY_DURATION_METER = "search_query_duration_seconds";

    /** Name of the Counter incremented when a search request hits the cache. */
    public static final String CACHE_HITS_METER = "search_cache_hits_total";

    /** Name of the Counter incremented when a search request misses the cache. */
    public static final String CACHE_MISSES_METER = "search_cache_misses_total";

    /** Tag key used to dimension {@link #QUERY_DURATION_METER} by hit/miss. */
    static final String TAG_CACHE_HIT = "cache_hit";

    private final Timer hitTimer;
    private final Timer missTimer;
    private final Counter hits;
    private final Counter misses;

    public SearchMetrics(MeterRegistry registry) {
        this.hitTimer = buildTimer(registry, true);
        this.missTimer = buildTimer(registry, false);
        this.hits = Counter.builder(CACHE_HITS_METER)
                .description("Number of search requests served from the cache")
                .register(registry);
        this.misses = Counter.builder(CACHE_MISSES_METER)
                .description("Number of search requests that bypassed the cache")
                .register(registry);
    }

    /**
     * Records the elapsed wall-clock duration of a search request against the
     * timer dimension matching {@code cacheHit}.
     *
     * @param elapsed  wall-clock time the request took
     * @param cacheHit {@code true} if the result came from the cache,
     *                 {@code false} otherwise
     */
    public void record(Duration elapsed, boolean cacheHit) {
        (cacheHit ? hitTimer : missTimer).record(elapsed);
    }

    /**
     * Increments {@value #CACHE_HITS_METER}. Caller is responsible for also
     * invoking {@link #record(Duration, boolean)} with {@code cacheHit=true}
     * so the timer and the counter stay in lockstep.
     */
    public void recordHit() {
        hits.increment();
    }

    /**
     * Increments {@value #CACHE_MISSES_METER}. Caller is responsible for also
     * invoking {@link #record(Duration, boolean)} with {@code cacheHit=false}
     * once the underlying repository call finishes.
     */
    public void recordMiss() {
        misses.increment();
    }

    private static Timer buildTimer(MeterRegistry registry, boolean cacheHit) {
        return Timer.builder(QUERY_DURATION_METER)
                .description("Wall-clock time spent serving a search request")
                .tag(TAG_CACHE_HIT, Boolean.toString(cacheHit))
                .register(registry);
    }
}
