package com.example.searchengine.infrastructure.admin;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory registry tracking the health state of each registered
 * {@link com.example.searchengine.domain.provider.ContentProvider}.
 *
 * <p>Updated by {@link com.example.searchengine.application.ingest.DefaultContentAggregator}
 * on every fetch outcome (success or failure). Read by the admin provider-health
 * endpoint to render the JSON response (REQ 2.1–2.5).</p>
 *
 * <p>State is process-local. For multi-instance deployments a follow-up
 * feature would back this with Redis, but per-instance visibility is
 * sufficient for the operator-tooling use case.</p>
 */
@Component
public class ProviderHealthRegistry {

    private final ConcurrentHashMap<String, ProviderHealthSnapshot> snapshots = new ConcurrentHashMap<>();
    private final Clock clock;

    public ProviderHealthRegistry(Clock clock) {
        this.clock = clock;
    }

    /**
     * Records a successful provider fetch.
     *
     * @param providerName the provider's registered name
     * @param fetchedItems number of items returned by the fetch
     */
    public void recordSuccess(String providerName, int fetchedItems) {
        Instant now = clock.instant();
        snapshots.compute(providerName, (name, existing) -> {
            ProviderHealthSnapshot current = existing != null ? existing : ProviderHealthSnapshot.initial(name);
            return current.withSuccess(now, fetchedItems);
        });
    }

    /**
     * Records a failed provider fetch.
     *
     * <p>The error message is sanitized and truncated to 256 characters
     * (REQ 2.4).</p>
     *
     * @param providerName the provider's registered name
     * @param error        the exception that caused the failure
     */
    public void recordFailure(String providerName, Throwable error) {
        Instant now = clock.instant();
        String message = error != null ? error.toString() : "unknown error";
        snapshots.compute(providerName, (name, existing) -> {
            ProviderHealthSnapshot current = existing != null ? existing : ProviderHealthSnapshot.initial(name);
            return current.withFailure(now, message);
        });
    }

    /**
     * Returns an unmodifiable view of all provider health snapshots.
     *
     * @return map keyed by provider name
     */
    public Map<String, ProviderHealthSnapshot> getSnapshots() {
        return Collections.unmodifiableMap(snapshots);
    }
}
