package com.example.searchengine.application.analytics;

/**
 * Port for persisting search analytics records.
 *
 * <p>Implementations live in {@code infrastructure/analytics/} and are
 * selected at startup via the {@code analytics.search.sink} property
 * ({@code db}, {@code log}, or {@code none}).</p>
 *
 * <p>REQ 4.1, 4.5</p>
 */
public interface SearchAnalyticsSink {

    /**
     * Persists a single analytics record.
     *
     * @param record the search analytics data to persist; never null
     */
    void record(SearchAnalyticsRecord record);
}
