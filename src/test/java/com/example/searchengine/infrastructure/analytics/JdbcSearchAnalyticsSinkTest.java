package com.example.searchengine.infrastructure.analytics;

import com.example.searchengine.application.analytics.SearchAnalyticsRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link JdbcSearchAnalyticsSink}.
 *
 * <p>Verifies the correct SQL statement and parameter binding using a
 * mocked {@link JdbcTemplate}.</p>
 */
class JdbcSearchAnalyticsSinkTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final JdbcSearchAnalyticsSink sink = new JdbcSearchAnalyticsSink(jdbcTemplate);

    @Test
    @DisplayName("record() calls JdbcTemplate.update with correct INSERT SQL")
    void recordCallsUpdateWithCorrectSql() {
        SearchAnalyticsRecord record = new SearchAnalyticsRecord(
                Instant.parse("2024-06-15T10:30:00Z"),
                "spring boot",
                "TEXT",
                "score",
                1,
                10,
                42L,
                15,
                false,
                "req-123",
                "a".repeat(64),
                null
        );

        sink.record(record);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sqlCaptor.capture(), any(Object[].class));

        String sql = sqlCaptor.getValue();
        assertThat(sql).containsIgnoringCase("INSERT INTO search_analytics");
        assertThat(sql).contains("requested_at");
        assertThat(sql).contains("q");
        assertThat(sql).contains("sort");
        assertThat(sql).contains("total_results");
        assertThat(sql).contains("latency_ms");
        assertThat(sql).contains("cache_hit");
        assertThat(sql).contains("request_id");
        assertThat(sql).contains("client_ip_hash");
        assertThat(sql).contains("error_code");
    }

    @Test
    @DisplayName("record() binds all parameters in correct order")
    void recordBindsParametersCorrectly() {
        Instant requestedAt = Instant.parse("2024-06-15T10:30:00Z");
        SearchAnalyticsRecord record = new SearchAnalyticsRecord(
                requestedAt,
                "java 21",
                "VIDEO",
                "date",
                2,
                25,
                100L,
                33,
                true,
                "req-xyz",
                "b".repeat(64),
                "INTERNAL_ERROR"
        );

        sink.record(record);

        verify(jdbcTemplate).update(
                any(String.class),
                eq(Timestamp.from(requestedAt)),  // requested_at
                eq("java 21"),                     // q
                eq("VIDEO"),                       // type
                eq("date"),                        // sort
                eq(2),                             // page
                eq(25),                            // limit
                eq(100L),                          // total_results
                eq(33),                            // latency_ms
                eq(true),                          // cache_hit
                eq("req-xyz"),                     // request_id
                eq("b".repeat(64)),                // client_ip_hash
                eq("INTERNAL_ERROR")               // error_code
        );
    }

    @Test
    @DisplayName("record() handles null optional fields (type, totalResults, errorCode)")
    void recordHandlesNullOptionalFields() {
        Instant requestedAt = Instant.parse("2024-01-01T00:00:00Z");
        SearchAnalyticsRecord record = new SearchAnalyticsRecord(
                requestedAt,
                "test",
                null,
                "score",
                1,
                10,
                null,
                5,
                false,
                null,
                null,
                null
        );

        sink.record(record);

        verify(jdbcTemplate).update(
                any(String.class),
                eq(Timestamp.from(requestedAt)),
                eq("test"),
                eq(null),       // type is null
                eq("score"),
                eq(1),
                eq(10),
                eq(null),       // total_results is null
                eq(5),
                eq(false),
                eq(null),       // request_id is null
                eq(null),       // client_ip_hash is null
                eq(null)        // error_code is null
        );
    }

    @Test
    @DisplayName("record() uses Instant.now() when requestedAt is null")
    void recordUsesInstantNowWhenRequestedAtIsNull() {
        SearchAnalyticsRecord record = new SearchAnalyticsRecord(
                null,
                "query",
                null,
                "score",
                1,
                10,
                50L,
                10,
                false,
                "req-1",
                "c".repeat(64),
                null
        );

        sink.record(record);

        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(any(String.class), argsCaptor.capture());

        Object[] args = argsCaptor.getValue();
        assertThat(args[0]).isInstanceOf(Timestamp.class);
        // The timestamp should be very recent (within last second)
        Timestamp ts = (Timestamp) args[0];
        assertThat(ts.toInstant()).isBetween(
                Instant.now().minusSeconds(5),
                Instant.now().plusSeconds(1)
        );
    }
}
