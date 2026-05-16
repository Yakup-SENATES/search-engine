package com.example.searchengine.domain.scoring;

/**
 * Immutable breakdown of all scoring components for a Content item.
 * Pure Java — no framework dependencies.
 *
 * <p>REQ 6.9</p>
 *
 * @param baseScore        raw base score (video: views/1000 + likes/100; text: readingTime + reactions/50)
 * @param typeMultiplier   multiplier applied to base score (video: 1.5; text: 1.0)
 * @param engagementScore  engagement metric (video: likes/views*10; text: reactions/readingTime*5; 0 if div-by-zero)
 * @param freshnessScore   time-decay bonus (5, 3, 1, or 0 based on content age)
 * @param finalScore       composite: (baseScore * typeMultiplier) + freshnessScore + engagementScore
 */
public record ScoreBreakdown(
        double baseScore,
        double typeMultiplier,
        double engagementScore,
        double freshnessScore,
        double finalScore
) {
}
