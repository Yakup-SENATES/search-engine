package com.example.searchengine.domain.scoring;

import com.example.searchengine.domain.content.Content;
import com.example.searchengine.domain.content.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * JUnit 5 example-based unit tests for {@link DefaultScoringEngine}.
 * Reproduces the worked examples from instructions.md § 47 and validates
 * each {@link ScoreBreakdown} component within 1e-4 tolerance (REQ 23.1).
 *
 * <p>Validates: Requirements 6.2, 6.3, 6.4, 6.5, 6.6, 6.7, 6.8, 6.9, 7.1, 7.2, 7.3, 7.4, 23.1</p>
 */
class DefaultScoringEngineTest {

    private static final double TOLERANCE = 1e-4;

    private DefaultScoringEngine engine;

    @BeforeEach
    void setUp() {
        engine = new DefaultScoringEngine();
    }

    /**
     * Helper to build a Content record with the given parameters.
     * Uses fixed evaluation-relative publishedAt for freshness control.
     */
    private Content buildContent(ContentType type, long views, long likes,
                                 int readingTime, long reactions, Instant publishedAt) {
        return new Content(
                UUID.randomUUID(),
                "test-provider",
                "ext-" + UUID.randomUUID(),
                "Test Title",
                "Test description",
                type,
                views,
                likes,
                readingTime,
                reactions,
                null,
                List.of(),
                publishedAt,
                0.0,
                0.0,
                0.0,
                Instant.now(),
                Instant.now()
        );
    }

    @Nested
    @DisplayName("Worked Examples from instructions.md § 47")
    class WorkedExamples {

        @Test
        @DisplayName("Video case: views=15000, likes=1200, 3 days old → final≈46.3")
        void videoWorkedExample() {
            // Given: video with views=15000, likes=1200, published 3 days ago
            Instant evaluationAt = Instant.parse("2024-06-15T12:00:00Z");
            Instant publishedAt = evaluationAt.minus(3, ChronoUnit.DAYS);

            Content video = buildContent(ContentType.VIDEO, 15000, 1200, 0, 0, publishedAt);

            // When
            ScoreBreakdown breakdown = engine.score(video, evaluationAt);

            // Then: baseScore = 15000/1000 + 1200/100 = 15 + 12 = 27
            assertEquals(27.0, breakdown.baseScore(), TOLERANCE);
            // typeMultiplier = 1.5 (VIDEO)
            assertEquals(1.5, breakdown.typeMultiplier(), TOLERANCE);
            // engagement = (1200/15000) * 10 = 0.8
            assertEquals(0.8, breakdown.engagementScore(), TOLERANCE);
            // freshness = 5 (3 days ≤ 7)
            assertEquals(5.0, breakdown.freshnessScore(), TOLERANCE);
            // final = (27 * 1.5) + 5 + 0.8 = 40.5 + 5 + 0.8 = 46.3
            assertEquals(46.3, breakdown.finalScore(), TOLERANCE);
        }

        @Test
        @DisplayName("Text case: readingTime=8, reactions=450, 3 days old → final≈303.25")
        void textWorkedExample() {
            // Given: text with readingTime=8, reactions=450, published 3 days ago
            Instant evaluationAt = Instant.parse("2024-06-15T12:00:00Z");
            Instant publishedAt = evaluationAt.minus(3, ChronoUnit.DAYS);

            Content text = buildContent(ContentType.TEXT, 0, 0, 8, 450, publishedAt);

            // When
            ScoreBreakdown breakdown = engine.score(text, evaluationAt);

            // Then: baseScore = 8 + 450/50 = 8 + 9 = 17
            assertEquals(17.0, breakdown.baseScore(), TOLERANCE);
            // typeMultiplier = 1.0 (TEXT)
            assertEquals(1.0, breakdown.typeMultiplier(), TOLERANCE);
            // engagement = (450/8) * 5 = 281.25
            assertEquals(281.25, breakdown.engagementScore(), TOLERANCE);
            // freshness = 5 (3 days ≤ 7)
            assertEquals(5.0, breakdown.freshnessScore(), TOLERANCE);
            // final = (17 * 1.0) + 5 + 281.25 = 17 + 5 + 281.25 = 303.25
            assertEquals(303.25, breakdown.finalScore(), TOLERANCE);
        }
    }

    @Nested
    @DisplayName("Division-by-zero guards (REQ 6.8)")
    class DivisionByZeroGuards {

        @Test
        @DisplayName("Video with views=0 → engagement=0")
        void videoZeroViews_engagementIsZero() {
            Instant evaluationAt = Instant.parse("2024-06-15T12:00:00Z");
            Instant publishedAt = evaluationAt.minus(1, ChronoUnit.DAYS);

            Content video = buildContent(ContentType.VIDEO, 0, 100, 0, 0, publishedAt);

            ScoreBreakdown breakdown = engine.score(video, evaluationAt);

            // engagement should be 0 when views=0 (division-by-zero guard)
            assertEquals(0.0, breakdown.engagementScore(), TOLERANCE);
            // baseScore = 0/1000 + 100/100 = 1.0
            assertEquals(1.0, breakdown.baseScore(), TOLERANCE);
        }

        @Test
        @DisplayName("Text with readingTime=0 → engagement=0")
        void textZeroReadingTime_engagementIsZero() {
            Instant evaluationAt = Instant.parse("2024-06-15T12:00:00Z");
            Instant publishedAt = evaluationAt.minus(1, ChronoUnit.DAYS);

            Content text = buildContent(ContentType.TEXT, 0, 0, 0, 500, publishedAt);

            ScoreBreakdown breakdown = engine.score(text, evaluationAt);

            // engagement should be 0 when readingTime=0 (division-by-zero guard)
            assertEquals(0.0, breakdown.engagementScore(), TOLERANCE);
            // baseScore = 0 + 500/50 = 10.0
            assertEquals(10.0, breakdown.baseScore(), TOLERANCE);
        }
    }

    @Nested
    @DisplayName("Freshness brackets (REQ 7.1–7.4)")
    class FreshnessBrackets {

        @Test
        @DisplayName("Content ≤ 7 days old → freshness=5 (REQ 7.1)")
        void freshness_within7Days() {
            Instant evaluationAt = Instant.parse("2024-06-15T12:00:00Z");
            Instant publishedAt = evaluationAt.minus(7, ChronoUnit.DAYS);

            Content content = buildContent(ContentType.VIDEO, 1000, 100, 0, 0, publishedAt);

            ScoreBreakdown breakdown = engine.score(content, evaluationAt);

            assertEquals(5.0, breakdown.freshnessScore(), TOLERANCE);
        }

        @Test
        @DisplayName("Content 8–30 days old → freshness=3 (REQ 7.2)")
        void freshness_within30Days() {
            Instant evaluationAt = Instant.parse("2024-06-15T12:00:00Z");
            Instant publishedAt = evaluationAt.minus(20, ChronoUnit.DAYS);

            Content content = buildContent(ContentType.VIDEO, 1000, 100, 0, 0, publishedAt);

            ScoreBreakdown breakdown = engine.score(content, evaluationAt);

            assertEquals(3.0, breakdown.freshnessScore(), TOLERANCE);
        }

        @Test
        @DisplayName("Content 31–90 days old → freshness=1 (REQ 7.3)")
        void freshness_within90Days() {
            Instant evaluationAt = Instant.parse("2024-06-15T12:00:00Z");
            Instant publishedAt = evaluationAt.minus(60, ChronoUnit.DAYS);

            Content content = buildContent(ContentType.VIDEO, 1000, 100, 0, 0, publishedAt);

            ScoreBreakdown breakdown = engine.score(content, evaluationAt);

            assertEquals(1.0, breakdown.freshnessScore(), TOLERANCE);
        }

        @Test
        @DisplayName("Content > 90 days old → freshness=0 (REQ 7.4)")
        void freshness_over90Days() {
            Instant evaluationAt = Instant.parse("2024-06-15T12:00:00Z");
            Instant publishedAt = evaluationAt.minus(100, ChronoUnit.DAYS);

            Content content = buildContent(ContentType.VIDEO, 1000, 100, 0, 0, publishedAt);

            ScoreBreakdown breakdown = engine.score(content, evaluationAt);

            assertEquals(0.0, breakdown.freshnessScore(), TOLERANCE);
        }
    }
}
