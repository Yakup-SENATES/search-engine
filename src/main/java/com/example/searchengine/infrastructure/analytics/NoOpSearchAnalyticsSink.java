package com.example.searchengine.infrastructure.analytics;

import com.example.searchengine.application.analytics.SearchAnalyticsRecord;
import com.example.searchengine.application.analytics.SearchAnalyticsSink;

/**
 * No-operation sink that discards all analytics records.
 *
 * <p>Selected when {@code analytics.search.sink=none} or when
 * {@code analytics.search.enabled=false}.</p>
 *
 * <p>REQ 4.6</p>
 */
public class NoOpSearchAnalyticsSink implements SearchAnalyticsSink {

    @Override
    public void record(SearchAnalyticsRecord record) {
        // intentionally empty — analytics disabled
    }
}
