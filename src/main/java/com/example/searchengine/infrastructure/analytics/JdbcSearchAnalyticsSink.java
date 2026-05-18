package com.example.searchengine.infrastructure.analytics;

import com.example.searchengine.application.analytics.SearchAnalyticsRecord;
import com.example.searchengine.application.analytics.SearchAnalyticsSink;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * Persists {@link SearchAnalyticsRecord} rows into the {@code search_analytics}
 * table using plain {@link JdbcTemplate} (no JPA overhead).
 *
 * <p>Selected when {@code analytics.search.sink=db} (the default).</p>
 *
 * <p>REQ 4.5</p>
 */
public class JdbcSearchAnalyticsSink implements SearchAnalyticsSink {

    private static final String INSERT_SQL = """
            INSERT INTO search_analytics
                (requested_at, q, type, sort, page, "limit",
                 total_results, latency_ms, cache_hit,
                 request_id, client_ip_hash, error_code)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcSearchAnalyticsSink(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void record(SearchAnalyticsRecord record) {
        jdbcTemplate.update(INSERT_SQL,
                Timestamp.from(record.requestedAt() != null ? record.requestedAt() : Instant.now()),
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
