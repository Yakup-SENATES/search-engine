package com.example.searchengine.domain.scoring;

import com.example.searchengine.domain.content.Content;
import com.example.searchengine.domain.content.ContentType;
import net.jqwik.api.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Property-based test for the scoring formula correctness.
 *
 * <p><b>Validates: Requirements 6.2, 6.3, 6.4, 6.5, 6.6, 6.7, 6.8, 6.9, 6.11, 7.1, 7.2, 7.3, 7.4</b></p>
 */
class ScoringEnginePropertyTest {

    private static final double TOLERANCE = 1e-9;
    private final DefaultScoringEngine engine = new DefaultScoringEngine();

    @Property(tries = 200)
    @Label("Feature: search-engine-service, Property 3: Scoring formula correctness")
    void scoringFormulaIsCorrect(@ForAll("validContentAndTimestamp") Tuple.Tuple2<Content, Instant> input) {
        Content content = input.get1();
        Instant evaluationAt = input.get2();

        ScoreBreakdown breakdown = engine.score(content, evaluationAt);

        // Verify baseScore
        double expectedBaseScore = computeExpectedBaseScore(content);
        assertThat(breakdown.baseScore()).isCloseTo(expectedBaseScore, within(TOLERANCE));

        // Verify typeMultiplier
        double expectedTypeMultiplier = computeExpectedTypeMultiplier(content);
        assertThat(breakdown.typeMultiplier()).isCloseTo(expectedTypeMultiplier, within(TOLERANCE));

        // Verify engagementScore
        double expectedEngagementScore = computeExpectedEngagementScore(content);
        assertThat(breakdown.engagementScore()).isCloseTo(expectedEngagementScore, within(TOLERANCE));

        // Verify freshnessScore
        double expectedFreshnessScore = computeExpectedFreshnessScore(content.publishedAt(), evaluationAt);
        assertThat(breakdown.freshnessScore()).isCloseTo(expectedFreshnessScore, within(TOLERANCE));

        // Verify finalScore = (baseScore * typeMultiplier) + freshnessScore + engagementScore
        double expectedFinalScore = (expectedBaseScore * expectedTypeMultiplier) + expectedFreshnessScore + expectedEngagementScore;
        assertThat(breakdown.finalScore()).isCloseTo(expectedFinalScore, within(TOLERANCE));
    }

    @Provide
    Arbitrary<Tuple.Tuple2<Content, Instant>> validContentAndTimestamp() {
        Arbitrary<Content> contentArb = Combinators.combine(
                Arbitraries.create(UUID::randomUUID),
                Arbitraries.of("provider1-json", "provider2-xml", "test-provider"),
                Arbitraries.strings().alpha().numeric().ofMinLength(1).ofMaxLength(20),
                Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(50),
                Arbitraries.of(ContentType.VIDEO, ContentType.TEXT),
                Arbitraries.longs().between(0, 1_000_000),
                Arbitraries.longs().between(0, 1_000_000),
                Arbitraries.integers().between(0, 10_000)
        ).flatAs((id, provider, externalId, title, type, views, likes, readingTime) ->
                Combinators.combine(
                        Arbitraries.longs().between(0, 1_000_000),
                        Arbitraries.longs()
                                .between(0, Instant.now().getEpochSecond())
                                .map(Instant::ofEpochSecond)
                ).as((reactions, publishedAt) ->
                        new Content(
                                id,
                                provider,
                                externalId,
                                title,
                                null,
                                type,
                                views,
                                likes,
                                readingTime,
                                reactions,
                                "PT10M",
                                List.of(),
                                publishedAt,
                                0.0,
                                0.0,
                                0.0,
                                Instant.now(),
                                Instant.now()
                        )
                )
        );

        Arbitrary<Instant> evaluationAtArb = Arbitraries.longs()
                .between(0, Instant.now().getEpochSecond() + 365L * 24 * 3600)
                .map(Instant::ofEpochSecond);

        return Combinators.combine(contentArb, evaluationAtArb).as(Tuple::of);
    }

    private double computeExpectedBaseScore(Content content) {
        return switch (content.type()) {
            case VIDEO -> content.views() / 1000.0 + content.likes() / 100.0;
            case TEXT -> content.readingTime() + content.reactions() / 50.0;
        };
    }

    private double computeExpectedTypeMultiplier(Content content) {
        return switch (content.type()) {
            case VIDEO -> 1.5;
            case TEXT -> 1.0;
        };
    }

    private double computeExpectedEngagementScore(Content content) {
        return switch (content.type()) {
            case VIDEO -> content.views() == 0
                    ? 0.0
                    : (double) content.likes() / content.views() * 10;
            case TEXT -> content.readingTime() == 0
                    ? 0.0
                    : (double) content.reactions() / content.readingTime() * 5;
        };
    }

    private double computeExpectedFreshnessScore(Instant publishedAt, Instant evaluationAt) {
        long days = ChronoUnit.DAYS.between(publishedAt, evaluationAt);
        if (days < 0) days = 0;
        if (days <= 7) return 5.0;
        if (days <= 30) return 3.0;
        if (days <= 90) return 1.0;
        return 0.0;
    }
}
