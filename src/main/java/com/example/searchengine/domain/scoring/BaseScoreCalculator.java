package com.example.searchengine.domain.scoring;

import com.example.searchengine.domain.content.Content;

/**
 * Computes the raw base score for a Content item based on its type and metrics.
 * Pure Java — no framework dependencies.
 *
 * <ul>
 *   <li>VIDEO: views / 1000.0 + likes / 100.0 (REQ 6.2)</li>
 *   <li>TEXT: readingTime + reactions / 50.0 (REQ 6.3)</li>
 * </ul>
 */
public final class BaseScoreCalculator {

    /**
     * Computes the base score for the given content.
     *
     * @param content the content to score, must not be null
     * @return the computed base score (always >= 0 for valid content)
     */
    public double compute(Content content) {
        return switch (content.type()) {
            case VIDEO -> content.views() / 1000.0 + content.likes() / 100.0;
            case TEXT -> content.readingTime() + content.reactions() / 50.0;
        };
    }
}
