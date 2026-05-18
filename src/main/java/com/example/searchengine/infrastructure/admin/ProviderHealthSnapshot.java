package com.example.searchengine.infrastructure.admin;

import java.time.Instant;

/**
 * Immutable snapshot of a single provider's health state.
 *
 * <p>Instances are stored in {@link ProviderHealthRegistry} and exposed
 * via the admin provider-health endpoint (REQ 2.1–2.5).</p>
 *
 * @param name             the provider's registered name
 * @param lastSyncAt       timestamp of the most recent sync attempt (null if never synced)
 * @param lastSyncOutcome  "success" or "failure" (null if never synced)
 * @param lastFetchedItems number of items returned by the last successful fetch
 * @param totalSuccesses   running count of successful syncs
 * @param totalFailures    running count of failed syncs
 * @param lastErrorMessage sanitized error message from the last failure (null if last was success or never synced)
 */
public record ProviderHealthSnapshot(
        String name,
        Instant lastSyncAt,
        String lastSyncOutcome,
        int lastFetchedItems,
        long totalSuccesses,
        long totalFailures,
        String lastErrorMessage
) {

    /** Maximum length for the error message field. */
    static final int MAX_ERROR_MESSAGE_LENGTH = 256;

    /**
     * Creates an initial empty snapshot for a provider that has never been synced.
     */
    static ProviderHealthSnapshot initial(String name) {
        return new ProviderHealthSnapshot(name, null, null, 0, 0L, 0L, null);
    }

    /**
     * Returns a new snapshot reflecting a successful sync.
     */
    ProviderHealthSnapshot withSuccess(Instant syncAt, int fetchedItems) {
        return new ProviderHealthSnapshot(
                this.name,
                syncAt,
                "success",
                fetchedItems,
                this.totalSuccesses + 1,
                this.totalFailures,
                null
        );
    }

    /**
     * Returns a new snapshot reflecting a failed sync.
     * The error message is truncated to {@value #MAX_ERROR_MESSAGE_LENGTH} characters.
     */
    ProviderHealthSnapshot withFailure(Instant syncAt, String errorMessage) {
        String sanitized = sanitizeErrorMessage(errorMessage);
        return new ProviderHealthSnapshot(
                this.name,
                syncAt,
                "failure",
                this.lastFetchedItems,
                this.totalSuccesses,
                this.totalFailures + 1,
                sanitized
        );
    }

    private static String sanitizeErrorMessage(String message) {
        if (message == null || message.isBlank()) {
            return "unknown error";
        }
        if (message.length() > MAX_ERROR_MESSAGE_LENGTH) {
            return message.substring(0, MAX_ERROR_MESSAGE_LENGTH);
        }
        return message;
    }
}
