package com.example.searchengine.application.ingest;

import java.time.Instant;
import java.util.List;

/**
 * Application-layer port for querying provider health state.
 *
 * <p>Used by the admin provider-health endpoint (REQ 2.1–2.5) to retrieve
 * the current state of all registered providers without the web layer
 * depending on infrastructure directly.</p>
 */
public interface ProviderHealthService {

    /**
     * Returns the health snapshot of every registered provider.
     *
     * @return list of provider health snapshots (never null, may be empty)
     */
    List<ProviderHealthInfo> getAll();

    /**
     * Immutable view of a single provider's health state.
     */
    record ProviderHealthInfo(
            String name,
            Instant lastSyncAt,
            String lastSyncOutcome,
            int lastFetchedItems,
            long totalSuccesses,
            long totalFailures,
            String lastErrorMessage
    ) {
    }
}
