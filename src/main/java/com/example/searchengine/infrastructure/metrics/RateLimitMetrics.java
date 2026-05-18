package com.example.searchengine.infrastructure.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Records rate-limit rejection counts.
 *
 * <p>Meter: {@code ratelimit_blocked_total} (Counter) tagged {@code path}.
 * Only the static API path prefix is exposed; client IP, request id, or any
 * other PII never reaches the registry (REQ 1.8).</p>
 *
 * <p>Validates: Requirements 1.7, 1.8 (operability quick-wins).</p>
 */
@Component
public class RateLimitMetrics {

    /** Name of the Counter incremented for each rate-limit rejection. */
    public static final String BLOCKED_METER = "ratelimit_blocked_total";

    static final String TAG_PATH = "path";

    /** Default tag value used when the supplied path prefix is null or blank. */
    static final String PATH_UNKNOWN = "unknown";

    private final MeterRegistry registry;

    public RateLimitMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * Increments {@value #BLOCKED_METER} for the given API path prefix.
     *
     * @param pathPrefix the static API path prefix (e.g.
     *                   {@code /api/v1/}); a {@code null} or blank value is
     *                   normalised to {@value #PATH_UNKNOWN} so the tag space
     *                   stays bounded.
     */
    public void recordBlocked(String pathPrefix) {
        String safePath = (pathPrefix == null || pathPrefix.isBlank()) ? PATH_UNKNOWN : pathPrefix;
        Counter.builder(BLOCKED_METER)
                .description("Number of requests rejected by the rate limiter")
                .tag(TAG_PATH, safePath)
                .register(registry)
                .increment();
    }
}
