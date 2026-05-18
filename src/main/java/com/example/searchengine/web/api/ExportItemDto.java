package com.example.searchengine.web.api;

/**
 * DTO for a single item in the JSON export response.
 *
 * <p>Mirrors the schema of {@code data[]} from {@link SearchResponse} with the
 * addition of {@code publishedAt} (ISO-8601 instant).</p>
 *
 * <p>REQ 5.2, 5.4</p>
 *
 * @param id          the unique identifier of the content
 * @param title       the content title
 * @param type        the content type as a lowercase string
 * @param score       the final score computed by the scoring engine
 * @param publishedAt the publication timestamp as ISO-8601
 */
public record ExportItemDto(
        String id,
        String title,
        String type,
        double score,
        String publishedAt
) {
}
