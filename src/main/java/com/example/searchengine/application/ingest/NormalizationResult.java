package com.example.searchengine.application.ingest;

import com.example.searchengine.domain.content.Content;

/**
 * Sealed result type for the normalization pipeline.
 *
 * <p>{@link Accepted} carries a fully validated {@link Content} domain entity
 * ready for scoring and persistence. {@link Rejected} carries the offending
 * field name and a human-readable reason so the caller can log and continue
 * without throwing (REQ 4.3, 4.4, 5.6).</p>
 */
public sealed interface NormalizationResult permits NormalizationResult.Accepted, NormalizationResult.Rejected {

    /**
     * The raw payload was valid and has been converted into a Content entity.
     */
    record Accepted(Content content) implements NormalizationResult {}

    /**
     * The raw payload failed validation. The item should be logged and skipped.
     *
     * @param field  the name of the field that caused rejection
     * @param reason a human-readable explanation of the failure
     */
    record Rejected(String field, String reason) implements NormalizationResult {}
}
