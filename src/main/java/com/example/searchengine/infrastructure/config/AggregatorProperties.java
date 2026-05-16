package com.example.searchengine.infrastructure.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Typed configuration properties for the background content aggregator.
 * Binds to the {@code aggregator} prefix in {@code application.yaml}.
 *
 * <p>Validates Requirements 11.1 and 11.5: the sync interval is configurable
 * with a sane lower bound and the scheduler can be globally toggled.</p>
 *
 * <ul>
 *   <li>{@code aggregator.sync.enabled} — defaults to {@code true};
 *       when {@code false}, the {@code SyncScheduler} bean is omitted (REQ 11.5).</li>
 *   <li>{@code aggregator.sync.fixed-delay-ms} — defaults to {@code 300000} (5 minutes);
 *       must be at least 1000 ms (REQ 11.1).</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "aggregator")
@Validated
public class AggregatorProperties {

    @Valid
    @NotNull
    private Sync sync = new Sync();

    public Sync getSync() {
        return sync;
    }

    public void setSync(Sync sync) {
        this.sync = sync;
    }

    /**
     * Nested configuration for the periodic synchronization run.
     */
    public static class Sync {

        /** REQ 11.5: enabled by default; when {@code false} the scheduler is not loaded. */
        private boolean enabled = true;

        /** REQ 11.1: delay between sync runs in milliseconds; minimum 1 second. */
        @Min(1000)
        private long fixedDelayMs = 300_000L;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getFixedDelayMs() {
            return fixedDelayMs;
        }

        public void setFixedDelayMs(long fixedDelayMs) {
            this.fixedDelayMs = fixedDelayMs;
        }
    }
}
