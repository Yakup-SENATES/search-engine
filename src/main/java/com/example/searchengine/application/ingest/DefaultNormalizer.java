package com.example.searchengine.application.ingest;

import com.example.searchengine.domain.content.Content;
import com.example.searchengine.domain.content.ContentType;
import com.example.searchengine.domain.provider.RawContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Default implementation of {@link Normalizer}.
 *
 * <p>Responsibilities:
 * <ol>
 *   <li>Sanitize title and description: strip Unicode Cc control characters
 *       except {@code \t} (0x09), {@code \n} (0x0A), {@code \r} (0x0D) — REQ 19.4</li>
 *   <li>Validate: non-blank title, non-blank externalId, valid type
 *       (via {@link ContentType#fromProviderValue}), non-null publishedAt,
 *       non-negative metrics — REQ 4.3, 4.4, 4.5</li>
 *   <li>On validation failure: return {@link NormalizationResult.Rejected} — do NOT throw</li>
 *   <li>On success: construct {@link Content} with a new UUID, provider name,
 *       and all mapped fields; return {@link NormalizationResult.Accepted}</li>
 * </ol>
 */
@Component
public class DefaultNormalizer implements Normalizer {

    private static final Logger log = LoggerFactory.getLogger(DefaultNormalizer.class);

    @Override
    public NormalizationResult normalize(String providerName, RawContent raw) {
        // --- Sanitize title and description ---
        String sanitizedTitle = sanitize(raw.title());
        String sanitizedDescription = sanitize(raw.description());

        // --- Validation ---
        if (raw.externalId() == null || raw.externalId().isBlank()) {
            return reject(providerName, raw, "externalId", "must not be blank");
        }

        if (sanitizedTitle == null || sanitizedTitle.isBlank()) {
            return reject(providerName, raw, "title", "must not be blank");
        }

        ContentType contentType;
        try {
            contentType = ContentType.fromProviderValue(raw.type());
        } catch (IllegalArgumentException e) {
            return reject(providerName, raw, "type", e.getMessage());
        }

        if (raw.publishedAt() == null) {
            return reject(providerName, raw, "publishedAt", "must not be null");
        }

        if (raw.views() < 0) {
            return reject(providerName, raw, "views", "must be non-negative");
        }
        if (raw.likes() < 0) {
            return reject(providerName, raw, "likes", "must be non-negative");
        }
        if (raw.readingTime() < 0) {
            return reject(providerName, raw, "readingTime", "must be non-negative");
        }
        if (raw.reactions() < 0) {
            return reject(providerName, raw, "reactions", "must be non-negative");
        }

        // --- Construct Content ---
        Content content = new Content(
                UUID.randomUUID(),
                providerName,
                raw.externalId(),
                sanitizedTitle,
                sanitizedDescription,
                contentType,
                raw.views(),
                raw.likes(),
                raw.readingTime(),
                raw.reactions(),
                raw.duration(),
                raw.tags(),
                raw.publishedAt(),
                0.0,   // finalScore — set by ScoringEngine
                0.0,   // popularityScore — set by ScoringEngine
                0.0,   // relevanceScore — set by query layer
                null,  // createdAt — set by persistence layer
                null   // updatedAt — set by persistence layer
        );

        return new NormalizationResult.Accepted(content);
    }

    /**
     * Strips Unicode Cc control characters from the input string, preserving
     * only {@code \t} (0x09), {@code \n} (0x0A), and {@code \r} (0x0D).
     *
     * @param s the input string (may be null)
     * @return the sanitized string, or null if input was null
     */
    String sanitize(String s) {
        if (s == null) {
            return null;
        }
        return s.codePoints()
                .filter(cp -> cp == 0x09 || cp == 0x0A || cp == 0x0D
                        || Character.getType(cp) != Character.CONTROL)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
    }

    private NormalizationResult.Rejected reject(String providerName, RawContent raw, String field, String reason) {
        log.warn("Normalization rejected: provider={}, externalId={}, field={}, reason={}",
                providerName,
                raw.externalId() != null ? raw.externalId() : "<null>",
                field,
                reason);
        return new NormalizationResult.Rejected(field, reason);
    }
}
