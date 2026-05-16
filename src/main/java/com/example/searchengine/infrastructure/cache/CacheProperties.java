package com.example.searchengine.infrastructure.cache;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Typed configuration properties for the search response cache.
 * Binds to the {@code cache.search} prefix in {@code application.yaml}.
 *
 * <p>Validates Requirements 12.2 and 12.5: TTL is configurable in the
 * documented range and the cache can be globally disabled via
 * {@code cache.search.enabled}.</p>
 *
 * <ul>
 *   <li>{@code cache.search.enabled} — defaults to {@code true};
 *       when {@code false}, a {@code NoOpCacheManager} is exposed so
 *       {@code @Cacheable} becomes a no-op (REQ 12.5).</li>
 *   <li>{@code cache.search.ttl-seconds} — defaults to {@code 300};
 *       must be between 1 and 86400 seconds inclusive (REQ 12.2).</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "cache.search")
@Validated
public class CacheProperties {

    /** REQ 12.5: enabled by default; when {@code false} a {@code NoOpCacheManager} is exposed. */
    private boolean enabled = true;

    /** REQ 12.2: TTL applied to entries in the {@code search} cache region. */
    @Min(1)
    @Max(86400)
    private long ttlSeconds = 300L;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getTtlSeconds() {
        return ttlSeconds;
    }

    public void setTtlSeconds(long ttlSeconds) {
        this.ttlSeconds = ttlSeconds;
    }
}
