package com.example.searchengine.application.analytics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link SearchAnalyticsRecorder}.
 *
 * <p>Verifies the best-effort wrapper behavior: successful delegation to
 * the sink and graceful exception swallowing on failure (REQ 4.4).</p>
 */
class SearchAnalyticsRecorderTest {

    private final SearchAnalyticsSink sink = mock(SearchAnalyticsSink.class);
    private final SearchAnalyticsRecorder recorder = new SearchAnalyticsRecorder(sink);

    private SearchAnalyticsRecord sampleRecord() {
        return new SearchAnalyticsRecord(
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
    }

    @Test
    @DisplayName("record() delegates to the sink on success")
    void recordDelegatesToSinkOnSuccess() {
        SearchAnalyticsRecord record = sampleRecord();

        recorder.record(record);

        verify(sink, times(1)).record(record);
    }

    @Test
    @DisplayName("record() does not propagate exceptions from the sink")
    void recordDoesNotPropagateExceptions() {
        SearchAnalyticsRecord record = sampleRecord();
        doThrow(new RuntimeException("DB connection failed"))
                .when(sink).record(record);

        assertThatCode(() -> recorder.record(record))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("record() catches checked exceptions wrapped in RuntimeException")
    void recordCatchesWrappedCheckedExceptions() {
        SearchAnalyticsRecord record = sampleRecord();
        doThrow(new RuntimeException("Timeout", new java.sql.SQLException("Connection reset")))
                .when(sink).record(record);

        assertThatCode(() -> recorder.record(record))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("record() still calls sink even after a previous failure")
    void recordStillCallsSinkAfterPreviousFailure() {
        SearchAnalyticsRecord record = sampleRecord();

        // First call throws
        doThrow(new RuntimeException("First failure"))
                .doNothing()
                .when(sink).record(record);

        recorder.record(record);  // fails silently
        recorder.record(record);  // should still call sink

        verify(sink, times(2)).record(record);
    }
}
