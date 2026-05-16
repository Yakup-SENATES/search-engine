package com.example.searchengine.web.api;

import com.example.searchengine.application.search.SearchResult;
import com.example.searchengine.domain.content.Content;

import java.util.List;

/**
 * Top-level response body for the Search API.
 *
 * <p>REQ 9.10, 9.11</p>
 *
 * @param data       the list of {@link ContentSummaryDto}s for the current page
 *                   (empty when no results match — REQ 9.11)
 * @param pagination the pagination metadata
 */
public record SearchResponse(
        List<ContentSummaryDto> data,
        PaginationDto pagination
) {
    /**
     * Compact constructor ensuring a non-null, immutable {@code data} list.
     */
    public SearchResponse {
        data = data == null ? List.of() : List.copyOf(data);
    }

    /**
     * Builds a {@code SearchResponse} from an application-layer {@link SearchResult}.
     * The web layer maps domain {@link Content} aggregates to {@link ContentSummaryDto}
     * here so that controllers never expose the domain aggregate directly (REQ 22.4, 22.5).
     *
     * @param result the application-level search result, must not be null
     * @return a fully populated {@code SearchResponse}
     */
    public static SearchResponse from(SearchResult result) {
        List<ContentSummaryDto> items = result.items().stream()
                .map(SearchResponse::toSummary)
                .toList();
        return new SearchResponse(
                items,
                new PaginationDto(result.page(), result.limit(), result.total())
        );
    }

    private static ContentSummaryDto toSummary(Content content) {
        return new ContentSummaryDto(
                content.id(),
                content.title(),
                content.type().name().toLowerCase(),
                content.finalScore()
        );
    }
}
