package com.example.searchengine.application.search;

import com.example.searchengine.domain.content.Content;

import java.util.List;

/**
 * Output record for the search use-case, wrapping the paginated results.
 *
 * <p>REQ 9.10, 9.11</p>
 *
 * @param items the content items on the current page
 * @param total the total number of matching items across all pages
 * @param page  the current page number (1-based)
 * @param limit the page size
 */
public record SearchResult(
        List<Content> items,
        long total,
        int page,
        int limit
) {
    /**
     * Compact constructor ensuring a non-null, immutable items list.
     */
    public SearchResult {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
