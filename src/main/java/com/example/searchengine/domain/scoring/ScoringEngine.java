package com.example.searchengine.domain.scoring;

import com.example.searchengine.domain.content.Content;
import java.time.Instant;

/**
 * Pure-Java domain interface for computing the deterministic Final_Score
 * of a Content snapshot at a given evaluation timestamp.
 *
 * <p>Implementations MUST NOT call {@code System.currentTimeMillis()} or
 * {@code Instant.now()} — the evaluation time is always an explicit parameter
 * (REQ 7.5).</p>
 *
 * <p>Invoking this method twice with the same Content snapshot and the same
 * {@code evaluationAt} MUST return identical numeric values (REQ 6.10).</p>
 *
 * <p>No Spring, JPA, Jackson, or other framework dependencies (REQ 6.1, REQ 22.2).</p>
 */
public interface ScoringEngine {

    /**
     * Computes the deterministic Final_Score for a Content snapshot.
     *
     * @param content      immutable Content snapshot, must not be null
     * @param evaluationAt evaluation timestamp, must not be null
     * @return ScoreBreakdown containing baseScore, typeMultiplier,
     *         engagementScore, freshnessScore, finalScore
     */
    ScoreBreakdown score(Content content, Instant evaluationAt);
}
