package com.example.searchengine.application.ingest;

/**
 * Orchestrates content synchronization: fetches from all registered providers,
 * normalizes, scores, and upserts content records.
 * <p>
 * Full implementation is provided by task 5.2. This interface defines the
 * contract used by the SyncScheduler.
 */
public interface ContentAggregator {

    /**
     * Executes a full sync run: fetches from every registered provider,
     * normalizes results, computes scores, and upserts into the repository.
     * <p>
     * Provider failures are isolated — one failing provider does not abort others.
     * Cache eviction occurs after a successful run.
     */
    void runSync();
}
