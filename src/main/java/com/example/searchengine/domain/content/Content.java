package com.example.searchengine.domain.content;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Immutable domain aggregate representing a piece of indexed content.
 * Pure Java — no framework annotations.
 *
 * <p>The compact constructor enforces all domain invariants at construction time:
 * non-blank identity fields, non-null required references, non-negative metrics,
 * and defensive copy of the tags list.</p>
 *
 * <p>REQ 4.1, 4.2, 4.3, 4.4, 4.5, 5.1</p>
 */
public record Content(
        UUID id,
        String provider,         // REQ 4.2
        String externalId,       // REQ 1, REQ 5.1 (unique with provider)
        String title,            // REQ 4.3 (non-empty)
        String description,
        ContentType type,        // REQ 4.5 (VIDEO or TEXT only)
        long views,              // REQ 4.4 (>= 0)
        long likes,              // REQ 4.4 (>= 0)
        int readingTime,         // REQ 4.4 (>= 0)
        long reactions,          // REQ 4.4 (>= 0)
        String duration,         // free-form for video, may be null
        List<String> tags,       // empty list if absent (REQ 3.5)
        Instant publishedAt,     // REQ 4.3 (valid)
        double finalScore,       // computed by ScoringEngine (REQ 6.9)
        double popularityScore,  // alias of base*multiplier+engagement
        double relevanceScore,   // populated by ts_rank at query time
        Instant createdAt,
        Instant updatedAt
) {
    /**
     * Compact constructor enforcing domain invariants.
     */
    public Content {
        if (provider == null || provider.isBlank()) throw new IllegalArgumentException("provider");
        if (externalId == null || externalId.isBlank()) throw new IllegalArgumentException("externalId");
        if (title == null || title.isBlank()) throw new IllegalArgumentException("title");
        if (type == null) throw new IllegalArgumentException("type");
        if (views < 0 || likes < 0 || readingTime < 0 || reactions < 0)
            throw new IllegalArgumentException("metrics must be >= 0");
        if (publishedAt == null) throw new IllegalArgumentException("publishedAt");
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}
