package com.example.searchengine.infrastructure.analytics;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.searchengine.application.analytics.SearchAnalyticsRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MarkerFactory;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link LogSearchAnalyticsSink}.
 *
 * <p>Verifies that {@code record(...)} logs at INFO level with the
 * {@code searchAnalytics} marker and includes the record fields in the
 * message.</p>
 */
class LogSearchAnalyticsSinkTest {

    private final LogSearchAnalyticsSink sink = new LogSearchAnalyticsSink();
    private ListAppender<ILoggingEvent> listAppender;
    private Logger logger;

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger(LogSearchAnalyticsSink.class);
        listAppender = new ListAppender<>();
        listAppender.start();
        logger.addAppender(listAppender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(listAppender);
        listAppender.stop();
    }

    @Test
    @DisplayName("record() logs at INFO level with searchAnalytics marker")
    void recordLogsAtInfoWithMarker() {
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
                "req-abc",
                "f".repeat(64),
                null
        );

        sink.record(record);

        assertThat(listAppender.list).hasSize(1);
        ILoggingEvent event = listAppender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        assertThat(event.getMarkerList()).isNotNull();
        assertThat(event.getMarkerList())
                .anyMatch(m -> m.contains(MarkerFactory.getMarker("searchAnalytics")));
    }

    @Test
    @DisplayName("record() message contains all record fields")
    void recordMessageContainsAllFields() {
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
                "a1b2c3".repeat(10) + "a1b2",
                null
        );

        sink.record(record);

        assertThat(listAppender.list).hasSize(1);
        String message = listAppender.list.get(0).getFormattedMessage();
        assertThat(message).contains("java 21");
        assertThat(message).contains("VIDEO");
        assertThat(message).contains("date");
        assertThat(message).contains("page=2");
        assertThat(message).contains("limit=25");
        assertThat(message).contains("totalResults=100");
        assertThat(message).contains("latencyMs=33");
        assertThat(message).contains("cacheHit=true");
        assertThat(message).contains("req-xyz");
    }

    @Test
    @DisplayName("record() handles null optional fields gracefully")
    void recordHandlesNullFields() {
        SearchAnalyticsRecord record = new SearchAnalyticsRecord(
                Instant.now(),
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
                "INTERNAL_ERROR"
        );

        sink.record(record);

        assertThat(listAppender.list).hasSize(1);
        ILoggingEvent event = listAppender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        String message = event.getFormattedMessage();
        assertThat(message).contains("errorCode=INTERNAL_ERROR");
    }
}
