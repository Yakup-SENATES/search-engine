package com.example.searchengine.domain.provider;

import java.util.List;

/**
 * Structured outcome of a provider fetch operation.
 * Wraps the list of successfully parsed items together with metadata
 * about the fetch (provider name, item count, any skipped-item count).
 *
 * <p>This type is useful when the caller needs to distinguish between
 * "provider returned zero items" and "provider failed entirely" without
 * relying on exceptions for control flow.</p>
 *
 * <p>Pure Java — no framework imports. REQ 1.1, REQ 22.2</p>
 *
 * @param providerName  the name of the provider that produced this result
 * @param items         successfully parsed raw content items (never null)
 * @param skippedCount  number of items that were skipped due to parse/validation errors
 * @param success       {@code true} if the fetch completed (even with partial results);
 *                      {@code false} if the provider was unreachable or returned a fatal error
 */
public record ProviderFetchResult(
        String providerName,
        List<RawContent> items,
        int skippedCount,
        boolean success
) {
    /**
     * Compact constructor that defensively copies the items list and defaults null to empty.
     */
    public ProviderFetchResult {
        items = items == null ? List.of() : List.copyOf(items);
    }

    /**
     * Creates a successful result with the given items and skipped count.
     */
    public static ProviderFetchResult success(String providerName, List<RawContent> items, int skippedCount) {
        return new ProviderFetchResult(providerName, items, skippedCount, true);
    }

    /**
     * Creates a failure result indicating the provider could not be reached or returned a fatal error.
     */
    public static ProviderFetchResult failure(String providerName) {
        return new ProviderFetchResult(providerName, List.of(), 0, false);
    }
}
