package com.example.searchengine.infrastructure.analytics;

import com.example.searchengine.application.analytics.SearchAnalyticsRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Unit tests for {@link NoOpSearchAnalyticsSink}.
 *
 * <p>Verifies that the no-op sink discards records without throwing or
 * producing side effects.</p>
 */
class NoOpSearchAnalyticsSinkTest {

    private final NoOpSearchAnalyticsSink sink = new NoOpSearchAnalyticsSink();

    @Test
    @DisplayName("record() does not throw for a valid record")
    void recordDoesNotThrow() {
        SearchAnalyticsRecord record = new SearchAnalyticsRecord(
                Instant.now(),
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

        assertDoesNotThrow(() -> sink.record(record));
    }

    @Test
    @DisplayName("record() does not throw for a record with null optional fields")
    void recordDoesNotThrowWithNulls() {
        SearchAnalyticsRecord record = new SearchAnalyticsRecord(
                Instant.now(),
                "test",
                null,
                "score",
                1,
                10,
                null,
                5,
                true,
                null,
                null,
                "INTERNAL_ERROR"
        );

        assertDoesNotThrow(() -> sink.record(record));
    }

    @Test
    @DisplayName("record() can be called multiple times without side effects")
    void recordMultipleTimesNoSideEffects() {
        SearchAnalyticsRecord record = new SearchAnalyticsRecord(
                Instant.now(),
                "query",
                null,
                "score",
                1,
                20,
                100L,
                8,
                false,
                "req-456",
                "b".repeat(64),
                null
        );

        assertDoesNotThrow(() -> {
            sink.record(record);
            sink.record(record);
            sink.record(record);
        });
    }
}
