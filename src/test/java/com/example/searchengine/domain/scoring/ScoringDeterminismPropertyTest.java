package com.example.searchengine.domain.scoring;

import com.example.searchengine.domain.content.Content;
import com.example.searchengine.domain.content.ContentType;
import net.jqwik.api.*;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test verifying scoring determinism.
 *
 * <p><b>Validates: Requirements 6.10, 6.11, 7.5</b></p>
 *
 * <p>For any Content snapshot c and any Instant t, two invocations of
 * DefaultScoringEngine.score(c, t) return identical ScoreBreakdown values
 * (bit-for-bit equal finalScore), and Content carries no field for freshnessScore
 * (freshness is recomputed every call).</p>
 */
class ScoringDeterminismPropertyTest {

    private final DefaultScoringEngine engine = new DefaultScoringEngine();

    @Property(tries = 100)
    @Label("Feature: search-engine-service, Property 4: Scoring determinism")
    void scoringIsDeterministic(
            @ForAll("validContent") Content content,
            @ForAll("evaluationInstant") Instant evaluationAt
    ) {
        ScoreBreakdown first = engine.score(content, evaluationAt);
        ScoreBreakdown second = engine.score(content, evaluationAt);

        // Bit-for-bit equality of finalScore (no tolerance — must be identical)
        assertThat(Double.doubleToLongBits(first.finalScore()))
                .as("finalScore must be bit-for-bit equal across invocations")
                .isEqualTo(Double.doubleToLongBits(second.finalScore()));

        // All components must also be identical
        assertThat(Double.doubleToLongBits(first.baseScore()))
                .isEqualTo(Double.doubleToLongBits(second.baseScore()));
        assertThat(Double.doubleToLongBits(first.typeMultiplier()))
                .isEqualTo(Double.doubleToLongBits(second.typeMultiplier()));
        assertThat(Double.doubleToLongBits(first.engagementScore()))
                .isEqualTo(Double.doubleToLongBits(second.engagementScore()));
        assertThat(Double.doubleToLongBits(first.freshnessScore()))
                .isEqualTo(Double.doubleToLongBits(second.freshnessScore()));
    }

    @Property(tries = 100)
    @Label("Feature: search-engine-service, Property 4: Content has no freshnessScore field")
    void contentHasNoFreshnessScoreField() {
        // Reflection check: Content record must NOT expose a freshnessScore field.
        // Freshness is recomputed every call by the ScoringEngine (REQ 6.11).
        boolean hasFreshnessField = Arrays.stream(Content.class.getDeclaredFields())
                .anyMatch(f -> f.getName().equals("freshnessScore"));

        assertThat(hasFreshnessField)
                .as("Content must not carry a freshnessScore field — freshness is recomputed every call (REQ 6.11)")
                .isFalse();
    }

    @Provide
    Arbitrary<Content> validContent() {
        Arbitrary<UUID> ids = Arbitraries.create(UUID::randomUUID);
        Arbitrary<String> providers = Arbitraries.of("provider1-json", "provider2-xml", "test-provider");
        Arbitrary<String> externalIds = Arbitraries.strings()
                .alpha().numeric()
                .ofMinLength(1).ofMaxLength(20);
        Arbitrary<String> titles = Arbitraries.strings()
                .alpha()
                .ofMinLength(1).ofMaxLength(50);
        Arbitrary<ContentType> types = Arbitraries.of(ContentType.VIDEO, ContentType.TEXT);
        Arbitrary<Long> views = Arbitraries.longs().between(0, 10_000_000);
        Arbitrary<Long> likes = Arbitraries.longs().between(0, 1_000_000);
        Arbitrary<Integer> readingTimes = Arbitraries.integers().between(0, 1000);
        Arbitrary<Long> reactions = Arbitraries.longs().between(0, 500_000);
        Arbitrary<List<String>> tags = Arbitraries.strings()
                .alpha().ofMinLength(1).ofMaxLength(10)
                .list().ofMaxSize(5);
        Arbitrary<Instant> publishedAts = Arbitraries.longs()
                .between(0, Instant.now().getEpochSecond())
                .map(Instant::ofEpochSecond);

        // jqwik Combinators.combine supports up to 8 parameters, so we split into two groups
        return Combinators.combine(ids, providers, externalIds, titles, types, views, likes, readingTimes)
                .flatAs((id, provider, externalId, title, type, v, l, rt) ->
                        Combinators.combine(reactions, tags, publishedAts)
                                .as((r, tagList, publishedAt) ->
                                        new Content(
                                                id, provider, externalId, title, null,
                                                type, v, l, rt, r,
                                                null, tagList, publishedAt,
                                                0.0, 0.0, 0.0,
                                                Instant.now(), Instant.now()
                                        )
                                )
                );
    }

    @Provide
    Arbitrary<Instant> evaluationInstant() {
        return Arbitraries.longs()
                .between(0, Instant.now().getEpochSecond() + 365L * 24 * 3600)
                .map(Instant::ofEpochSecond);
    }
}
