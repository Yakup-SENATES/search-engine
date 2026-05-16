package com.example.searchengine.web.api;

import java.util.UUID;

/**
 * Compact summary of a {@code Content} item exposed by the Search API.
 *
 * <p>The web layer never returns the domain {@code Content} aggregate
 * directly (REQ 22.4, 22.5). This DTO carries only the fields documented
 * in the Search API response contract.</p>
 *
 * <p>REQ 9.10</p>
 *
 * @param id    the unique identifier of the content
 * @param title the content title
 * @param type  the content type as a lowercase string (e.g. {@code "video"} or {@code "text"})
 * @param score the {@code final_score} computed by the scoring engine
 */
public record ContentSummaryDto(
        UUID id,
        String title,
        String type,
        double score
) {
}
