package com.example.searchengine.domain.content;

import java.util.List;

/**
 * Paginated search result returned by {@link ContentRepository#search(SearchCriteria)}.
 *
 * <p>REQ 9.10, 9.11</p>
 *
 * @param items the content items on the current page
 * @param total the total number of matching items across all pages
 */
public record SearchPage(
        List<Content> items,
        long total
) {
    /**
     * Compact constructor ensuring a non-null, immutable items list.
     */
    public SearchPage {
        items = items == null ? List.of() : List.copyOf(items);
        if (total < 0) {
            throw new IllegalArgumentException("total must be >= 0");
        }
    }
}
