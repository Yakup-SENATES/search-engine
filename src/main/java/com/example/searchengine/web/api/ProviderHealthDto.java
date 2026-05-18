package com.example.searchengine.web.api;

import java.time.Instant;

/**
 * DTO representing the health state of a single content provider.
 *
 * <p>Carries the seven public fields required by REQ 2 (ACs 2.1–2.5):
 * <ul>
 *   <li>{@code name} — the provider's registered name</li>
 *   <li>{@code lastSyncAt} — ISO-8601 instant of the most recent sync (null if never synced)</li>
 *   <li>{@code lastSyncOutcome} — "success" or "failure" (null if never synced)</li>
 *   <li>{@code lastFetchedItems} — number of items returned by the last successful fetch</li>
 *   <li>{@code totalSuccesses} — running count of successful syncs</li>
 *   <li>{@code totalFailures} — running count of failed syncs</li>
 *   <li>{@code lastErrorMessage} — sanitized error message from the last failure (null if last was success or never synced)</li>
 * </ul>
 */
public record ProviderHealthDto(
        String name,
        Instant lastSyncAt,
        String lastSyncOutcome,
        int lastFetchedItems,
        long totalSuccesses,
        long totalFailures,
        String lastErrorMessage
) {
}
