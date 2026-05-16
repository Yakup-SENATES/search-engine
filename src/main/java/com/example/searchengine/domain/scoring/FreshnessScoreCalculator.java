package com.example.searchengine.domain.scoring;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Computes the freshness bonus based on content age relative to the evaluation timestamp.
 * Pure Java — no framework dependencies.
 *
 * <ul>
 *   <li>≤ 7 days: 5 (REQ 7.1)</li>
 *   <li>≤ 30 days: 3 (REQ 7.2)</li>
 *   <li>≤ 90 days: 1 (REQ 7.3)</li>
 *   <li>&gt; 90 days: 0 (REQ 7.4)</li>
 * </ul>
 *
 * <p>The evaluation timestamp is always an explicit parameter (REQ 7.5).
 * Future-dated content (negative age) is treated as maximally fresh.</p>
 */
public final class FreshnessScoreCalculator {

    /**
     * Computes the freshness score for content published at the given instant,
     * evaluated at the specified time.
     *
     * @param publishedAt the content's publication timestamp, must not be null
     * @param evaluationAt the evaluation timestamp, must not be null
     * @return freshness bonus: 5, 3, 1, or 0
     */
    public double compute(Instant publishedAt, Instant evaluationAt) {
        long days = ChronoUnit.DAYS.between(publishedAt, evaluationAt);
        if (days < 0) {
            days = 0; // future-dated content treated as fresh
        }
        if (days <= 7) return 5.0;
        if (days <= 30) return 3.0;
        if (days <= 90) return 1.0;
        return 0.0;
    }
}
