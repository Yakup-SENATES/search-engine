package com.example.searchengine.domain.provider;

import java.time.Instant;
import java.util.List;

/**
 * Provider-agnostic raw payload carrying the union of all mapped fields
 * from both the JSON and XML providers.
 *
 * <p>Field origins:
 * <ul>
 *   <li>JSON: id → externalId, title, type, metrics.views → views,
 *       metrics.likes → likes, metrics.duration → duration,
 *       published_at → publishedAt, tags</li>
 *   <li>XML: id → externalId, headline → title, type, stats.views → views,
 *       stats.likes → likes, stats.reading_time → readingTime,
 *       stats.reactions → reactions, publication_date → publishedAt,
 *       categories → tags</li>
 * </ul>
 *
 * <p>Fields that do not apply to a given provider are set to their
 * zero/null/empty defaults (e.g. JSON items have {@code readingTime = 0}
 * and {@code reactions = 0}; XML items may have {@code duration = null}).
 *
 * <p>Pure Java — no framework imports. REQ 2.3, REQ 3.2, REQ 22.2</p>
 *
 * @param externalId   unique identifier from the provider (required)
 * @param title        content title / headline (required)
 * @param description  optional description or summary, may be null
 * @param type         raw type string as received from the provider (e.g. "video", "article")
 * @param views        view count metric (>= 0)
 * @param likes        like count metric (>= 0)
 * @param readingTime  reading time in minutes for text content (>= 0, 0 for video)
 * @param reactions    reaction count for text content (>= 0, 0 for video)
 * @param duration     duration string for video content (null for text)
 * @param tags         list of tags/categories (empty list if absent)
 * @param publishedAt  publication timestamp (required)
 */
public record RawContent(
        String externalId,
        String title,
        String description,
        String type,
        long views,
        long likes,
        int readingTime,
        long reactions,
        String duration,
        List<String> tags,
        Instant publishedAt
) {
    /**
     * Compact constructor that defensively copies the tags list and defaults null to empty.
     */
    public RawContent {
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}
