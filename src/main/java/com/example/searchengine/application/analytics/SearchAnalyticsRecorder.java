package com.example.searchengine.application.analytics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Best-effort wrapper around a {@link SearchAnalyticsSink}.
 *
 * <p>Delegates to the configured sink inside a {@code try/catch} so that
 * analytics persistence failures never propagate to the caller. On any
 * exception the error is logged at WARN level and silently swallowed
 * (fire-and-forget per REQ 4.4).</p>
 *
 * <p>REQ 4.4</p>
 */
@Component
public class SearchAnalyticsRecorder {

    private static final Logger log = LoggerFactory.getLogger(SearchAnalyticsRecorder.class);

    private final SearchAnalyticsSink sink;

    public SearchAnalyticsRecorder(SearchAnalyticsSink sink) {
        this.sink = sink;
    }

    /**
     * Records a search analytics event in a best-effort manner.
     *
     * <p>If the underlying sink throws any exception, the error is logged
     * at WARN level and the exception is <strong>not</strong> propagated.</p>
     *
     * @param record the analytics data to persist; never null
     */
    public void record(SearchAnalyticsRecord record) {
        try {
            sink.record(record);
        } catch (Exception ex) {
            log.warn("Failed to record search analytics (best-effort, swallowed): {}", ex.getMessage(), ex);
        }
    }
}
