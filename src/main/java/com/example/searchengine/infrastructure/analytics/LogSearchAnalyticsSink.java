package com.example.searchengine.infrastructure.analytics;

import com.example.searchengine.application.analytics.SearchAnalyticsRecord;
import com.example.searchengine.application.analytics.SearchAnalyticsSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

/**
 * Emits each {@link SearchAnalyticsRecord} as a single structured-log line at
 * INFO level with the marker {@code searchAnalytics}.
 *
 * <p>All record fields are rendered as key=value pairs so downstream log
 * shippers can parse them without additional configuration.</p>
 *
 * <p>Selected when {@code analytics.search.sink=log}.</p>
 *
 * <p>REQ 4.5</p>
 */
public class LogSearchAnalyticsSink implements SearchAnalyticsSink {

    private static final Logger log = LoggerFactory.getLogger(LogSearchAnalyticsSink.class);
    private static final Marker MARKER = MarkerFactory.getMarker("searchAnalytics");

    @Override
    public void record(SearchAnalyticsRecord record) {
        log.info(MARKER,
                "requestedAt={} q={} type={} sort={} page={} limit={} "
                        + "totalResults={} latencyMs={} cacheHit={} "
                        + "requestId={} clientIpHash={} errorCode={}",
                record.requestedAt(),
                record.q(),
                record.type(),
                record.sort(),
                record.page(),
                record.limit(),
                record.totalResults(),
                record.latencyMs(),
                record.cacheHit(),
                record.requestId(),
                record.clientIpHash(),
                record.errorCode()
        );
    }
}
