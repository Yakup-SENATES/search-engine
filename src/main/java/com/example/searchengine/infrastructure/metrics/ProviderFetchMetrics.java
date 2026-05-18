package com.example.searchengine.infrastructure.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Records timer + failure counter samples around a provider's
 * {@code fetch()} invocation.
 *
 * <p>Meter taxonomy (also surfaced through {@code /actuator/prometheus}):
 * <ul>
 *   <li>{@value #FETCH_DURATION_METER} (Timer) tagged
 *       {@code provider, outcome} where {@code outcome} is
 *       {@code success} or {@code failure}.</li>
 *   <li>{@value #FETCH_FAILURES_METER} (Counter) tagged
 *       {@code provider}, incremented on any failed fetch.</li>
 * </ul>
 *
 * <p>Timers (success + failure) and the failures counter for the two shipped
 * providers ({@code provider1-json}, {@code provider2-xml}) are eagerly
 * registered in the constructor so Prometheus dashboards see all six series
 * before the first fetch lands. Unknown provider names are accepted at the
 * call site and registered lazily on first use.</p>
 *
 * <p>Tag values are sourced exclusively from the static, non-PII
 * {@link com.example.searchengine.domain.provider.ContentProvider#name()}
 * identifier; user-supplied data never reaches the registry (REQ 1.8).</p>
 *
 * <p>Validates: Requirements 1.2, 1.3, 1.8 (operability quick-wins).</p>
 */
@Component
public class ProviderFetchMetrics {

    /** Name of the Timer that captures fetch latency. */
    public static final String FETCH_DURATION_METER = "provider_fetch_duration_seconds";

    /** Name of the Counter incremented on any failed fetch. */
    public static final String FETCH_FAILURES_METER = "provider_fetch_failures_total";

    static final String TAG_PROVIDER = "provider";
    static final String TAG_OUTCOME = "outcome";
    static final String OUTCOME_SUCCESS = "success";
    static final String OUTCOME_FAILURE = "failure";

    /**
     * Names of the providers shipped with the service. Used to eagerly
     * pre-register the success/failure timers and the failure counter so
     * dashboards see all six series before the first fetch completes.
     */
    private static final String[] SHIPPED_PROVIDERS = {"provider1-json", "provider2-xml"};

    private final MeterRegistry registry;

    public ProviderFetchMetrics(MeterRegistry registry) {
        this.registry = registry;
        for (String provider : SHIPPED_PROVIDERS) {
            timer(provider, OUTCOME_SUCCESS);
            timer(provider, OUTCOME_FAILURE);
            failureCounter(provider);
        }
    }

    /**
     * Records the elapsed wall-clock time of a single provider fetch.
     *
     * <p>The {@value #FETCH_DURATION_METER} timer matching {@code success}
     * is updated with the elapsed duration. On failure, the
     * {@value #FETCH_FAILURES_METER} counter is also incremented.</p>
     *
     * @param providerName static {@code ContentProvider.name()} value
     * @param elapsed      wall-clock time the fetch took
     * @param success      {@code true} for a successful fetch,
     *                     {@code false} otherwise
     */
    public void record(String providerName, Duration elapsed, boolean success) {
        String outcome = success ? OUTCOME_SUCCESS : OUTCOME_FAILURE;
        timer(providerName, outcome).record(elapsed);
        if (!success) {
            failureCounter(providerName).increment();
        }
    }

    /**
     * Increments only the {@value #FETCH_FAILURES_METER} counter for the
     * given provider, leaving the duration timers untouched. Useful when
     * a failure is detected without a meaningful elapsed-time measurement.
     *
     * @param providerName static {@code ContentProvider.name()} value
     */
    public void recordFailure(String providerName) {
        failureCounter(providerName).increment();
    }

    private Timer timer(String providerName, String outcome) {
        return Timer.builder(FETCH_DURATION_METER)
                .description("Wall-clock time spent fetching from a content provider")
                .tag(TAG_PROVIDER, providerName)
                .tag(TAG_OUTCOME, outcome)
                .register(registry);
    }

    private Counter failureCounter(String providerName) {
        return Counter.builder(FETCH_FAILURES_METER)
                .description("Number of provider fetch invocations that failed")
                .tag(TAG_PROVIDER, providerName)
                .register(registry);
    }
}
