package com.example.searchengine.infrastructure.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ProviderFetchMetrics} backed by a {@link SimpleMeterRegistry}.
 *
 * <p>Validates Requirements 1.2 and 1.3 (operability quick-wins): the meter
 * names, tag schemas, and counter increments match the contract defined in the
 * spec.</p>
 */
class ProviderFetchMetricsTest {

    private MeterRegistry registry;
    private ProviderFetchMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new ProviderFetchMetrics(registry);
    }

    @Test
    @DisplayName("Constructor pre-registers timers and counters for the shipped providers (REQ 1.2, 1.3)")
    void preRegistersMetersForShippedProviders() {
        // Both timers (success + failure) and the failures counter must exist
        // before any record() call so /actuator/prometheus exposes them at boot.
        assertThat(registry.find(ProviderFetchMetrics.FETCH_DURATION_METER)
                .tag("provider", "provider1-json").tag("outcome", "success").timer())
                .isNotNull();
        assertThat(registry.find(ProviderFetchMetrics.FETCH_DURATION_METER)
                .tag("provider", "provider1-json").tag("outcome", "failure").timer())
                .isNotNull();
        assertThat(registry.find(ProviderFetchMetrics.FETCH_DURATION_METER)
                .tag("provider", "provider2-xml").tag("outcome", "success").timer())
                .isNotNull();
        assertThat(registry.find(ProviderFetchMetrics.FETCH_DURATION_METER)
                .tag("provider", "provider2-xml").tag("outcome", "failure").timer())
                .isNotNull();

        assertThat(registry.find(ProviderFetchMetrics.FETCH_FAILURES_METER)
                .tag("provider", "provider1-json").counter())
                .isNotNull();
        assertThat(registry.find(ProviderFetchMetrics.FETCH_FAILURES_METER)
                .tag("provider", "provider2-xml").counter())
                .isNotNull();
    }

    @Test
    @DisplayName("record(success=true) updates the success-tagged timer and not the failures counter (REQ 1.2)")
    void recordSuccessUpdatesSuccessTimerOnly() {
        metrics.record("provider1-json", Duration.ofMillis(150), true);

        Timer successTimer = registry.find(ProviderFetchMetrics.FETCH_DURATION_METER)
                .tag("provider", "provider1-json").tag("outcome", "success").timer();
        assertThat(successTimer).isNotNull();
        assertThat(successTimer.count()).isEqualTo(1L);
        assertThat(successTimer.totalTime(TimeUnit.MILLISECONDS)).isCloseTo(150.0, org.assertj.core.data.Offset.offset(1.0));

        Counter failures = registry.find(ProviderFetchMetrics.FETCH_FAILURES_METER)
                .tag("provider", "provider1-json").counter();
        assertThat(failures).isNotNull();
        assertThat(failures.count()).isZero();

        Timer failureTimer = registry.find(ProviderFetchMetrics.FETCH_DURATION_METER)
                .tag("provider", "provider1-json").tag("outcome", "failure").timer();
        assertThat(failureTimer).isNotNull();
        assertThat(failureTimer.count()).isZero();
    }

    @Test
    @DisplayName("record(success=false) updates the failure-tagged timer AND increments failures counter (REQ 1.2, 1.3)")
    void recordFailureUpdatesFailureTimerAndCounter() {
        metrics.record("provider2-xml", Duration.ofMillis(80), false);

        Timer failureTimer = registry.find(ProviderFetchMetrics.FETCH_DURATION_METER)
                .tag("provider", "provider2-xml").tag("outcome", "failure").timer();
        assertThat(failureTimer).isNotNull();
        assertThat(failureTimer.count()).isEqualTo(1L);

        Counter failures = registry.find(ProviderFetchMetrics.FETCH_FAILURES_METER)
                .tag("provider", "provider2-xml").counter();
        assertThat(failures).isNotNull();
        assertThat(failures.count()).isEqualTo(1.0);

        Timer successTimer = registry.find(ProviderFetchMetrics.FETCH_DURATION_METER)
                .tag("provider", "provider2-xml").tag("outcome", "success").timer();
        assertThat(successTimer).isNotNull();
        assertThat(successTimer.count()).isZero();
    }

    @Test
    @DisplayName("recordFailure increments only the failures counter, leaving timers untouched (REQ 1.3)")
    void recordFailureOnlyIncrementsCounter() {
        metrics.recordFailure("provider1-json");
        metrics.recordFailure("provider1-json");

        Counter failures = registry.find(ProviderFetchMetrics.FETCH_FAILURES_METER)
                .tag("provider", "provider1-json").counter();
        assertThat(failures).isNotNull();
        assertThat(failures.count()).isEqualTo(2.0);

        Timer failureTimer = registry.find(ProviderFetchMetrics.FETCH_DURATION_METER)
                .tag("provider", "provider1-json").tag("outcome", "failure").timer();
        assertThat(failureTimer).isNotNull();
        assertThat(failureTimer.count()).isZero();
    }

    @Test
    @DisplayName("Recording an unknown provider name lazily registers its timers + counter (REQ 1.2, 1.3)")
    void recordCreatesMetersForUnknownProviders() {
        metrics.record("provider3-rss", Duration.ofMillis(40), true);
        metrics.record("provider3-rss", Duration.ofMillis(20), false);

        Timer success = registry.find(ProviderFetchMetrics.FETCH_DURATION_METER)
                .tag("provider", "provider3-rss").tag("outcome", "success").timer();
        Timer failure = registry.find(ProviderFetchMetrics.FETCH_DURATION_METER)
                .tag("provider", "provider3-rss").tag("outcome", "failure").timer();
        Counter failureCounter = registry.find(ProviderFetchMetrics.FETCH_FAILURES_METER)
                .tag("provider", "provider3-rss").counter();

        assertThat(success).isNotNull();
        assertThat(success.count()).isEqualTo(1L);
        assertThat(failure).isNotNull();
        assertThat(failure.count()).isEqualTo(1L);
        assertThat(failureCounter).isNotNull();
        assertThat(failureCounter.count()).isEqualTo(1.0);
    }
}
