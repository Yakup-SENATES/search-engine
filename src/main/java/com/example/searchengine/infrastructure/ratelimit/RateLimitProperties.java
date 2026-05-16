package com.example.searchengine.infrastructure.ratelimit;

import jakarta.validation.constraints.Min;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Typed configuration properties for the rate limiter.
 * Binds to the {@code ratelimit} prefix in {@code application.yaml}.
 *
 * <p>Validates Requirements 13.2, 13.4, 13.5: configurable per-window request budget
 * and an enable/disable toggle. Defaults match the values quoted in the design document
 * (100 requests / 60 seconds, enabled).
 */
@ConfigurationProperties(prefix = "ratelimit")
@Validated
public class RateLimitProperties {

    /** REQ 13.5: enabled by default; when {@code false} the logging-only filter is loaded instead. */
    private boolean enabled = true;

    /** REQ 13.2 / 13.4: token bucket capacity per client IP. */
    @Min(1)
    private int requestsPerWindow = 100;

    /** REQ 13.2 / 13.4: window length in seconds (also the bucket's refill period). */
    @Min(1)
    private long windowSeconds = 60L;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getRequestsPerWindow() {
        return requestsPerWindow;
    }

    public void setRequestsPerWindow(int requestsPerWindow) {
        this.requestsPerWindow = requestsPerWindow;
    }

    public long getWindowSeconds() {
        return windowSeconds;
    }

    public void setWindowSeconds(long windowSeconds) {
        this.windowSeconds = windowSeconds;
    }

    /**
     * Convenience accessor returning {@link #getWindowSeconds()} as a {@link Duration}
     * for use with Bucket4j's {@code Bandwidth} API.
     */
    public Duration windowDuration() {
        return Duration.ofSeconds(windowSeconds);
    }
}
