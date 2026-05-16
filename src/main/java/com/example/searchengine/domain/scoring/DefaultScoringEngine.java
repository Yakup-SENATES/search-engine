package com.example.searchengine.domain.scoring;

import com.example.searchengine.domain.content.Content;
import java.time.Instant;

/**
 * Default implementation of {@link ScoringEngine} that composes four pure-Java
 * sub-calculators to produce a deterministic final score.
 *
 * <p>Formula: {@code finalScore = (baseScore * typeMultiplier) + freshnessScore + engagementScore}
 * (REQ 6.9)</p>
 *
 * <p>Pure Java — no Spring, JPA, Jackson, or other framework dependencies (REQ 6.1, REQ 22.2).
 * The evaluation timestamp is always an explicit parameter (REQ 7.5).
 * Freshness is recomputed on every call (REQ 6.11).</p>
 */
public final class DefaultScoringEngine implements ScoringEngine {

    private final BaseScoreCalculator base;
    private final TypeMultiplier multiplier;
    private final EngagementScoreCalculator engagement;
    private final FreshnessScoreCalculator freshness;

    public DefaultScoringEngine() {
        this.base = new BaseScoreCalculator();
        this.multiplier = new TypeMultiplier();
        this.engagement = new EngagementScoreCalculator();
        this.freshness = new FreshnessScoreCalculator();
    }

    public DefaultScoringEngine(
            BaseScoreCalculator base,
            TypeMultiplier multiplier,
            EngagementScoreCalculator engagement,
            FreshnessScoreCalculator freshness) {
        this.base = base;
        this.multiplier = multiplier;
        this.engagement = engagement;
        this.freshness = freshness;
    }

    @Override
    public ScoreBreakdown score(Content content, Instant evaluationAt) {
        double bs = base.compute(content);
        double tm = multiplier.of(content.type());
        double es = engagement.compute(content);
        double fs = freshness.compute(content.publishedAt(), evaluationAt);
        double finalScore = (bs * tm) + fs + es;
        return new ScoreBreakdown(bs, tm, es, fs, finalScore);
    }
}
