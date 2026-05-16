package com.example.searchengine.domain.scoring;

import com.example.searchengine.domain.content.Content;

/**
 * Computes the engagement score for a Content item based on interaction-to-consumption ratios.
 * Pure Java — no framework dependencies.
 *
 * <ul>
 *   <li>VIDEO (views > 0): (likes / views) * 10 (REQ 6.6)</li>
 *   <li>TEXT (readingTime > 0): (reactions / readingTime) * 5 (REQ 6.7)</li>
 *   <li>Division-by-zero guard: returns 0 when denominator is zero (REQ 6.8)</li>
 * </ul>
 */
public final class EngagementScoreCalculator {

    /**
     * Computes the engagement score for the given content.
     *
     * @param content the content to score, must not be null
     * @return the engagement score, or 0 if the denominator is zero
     */
    public double compute(Content content) {
        return switch (content.type()) {
            case VIDEO -> content.views() == 0
                    ? 0.0
                    : (double) content.likes() / content.views() * 10;
            case TEXT -> content.readingTime() == 0
                    ? 0.0
                    : (double) content.reactions() / content.readingTime() * 5;
        };
    }
}
