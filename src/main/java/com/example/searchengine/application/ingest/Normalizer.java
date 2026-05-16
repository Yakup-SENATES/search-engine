package com.example.searchengine.application.ingest;

import com.example.searchengine.domain.provider.RawContent;

/**
 * Converts a raw provider payload into a validated {@link com.example.searchengine.domain.content.Content}
 * domain entity wrapped in a {@link NormalizationResult}.
 *
 * <p>Implementations perform sanitization (stripping Unicode Cc control characters
 * except {@code \t}, {@code \n}, {@code \r} from title and description — REQ 19.4)
 * and validation (non-blank title/externalId, valid type, non-null publishedAt,
 * non-negative metrics — REQ 4.3, 4.4, 4.5). Failures produce
 * {@link NormalizationResult.Rejected} rather than throwing, so batch processing
 * can continue (REQ 5.6).</p>
 */
public interface Normalizer {

    /**
     * Validates and converts a raw provider payload into a Content domain entity.
     *
     * @param providerName the name of the originating provider (REQ 4.2)
     * @param raw          the raw content payload to normalize
     * @return {@link NormalizationResult.Accepted} on success,
     *         {@link NormalizationResult.Rejected} on validation failure
     */
    NormalizationResult normalize(String providerName, RawContent raw);
}
