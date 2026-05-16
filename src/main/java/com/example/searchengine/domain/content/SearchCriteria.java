package com.example.searchengine.domain.content;

/**
 * Value object encapsulating the parameters for a content search query.
 *
 * <p>REQ 8.1, 9.1, 9.3–9.8</p>
 *
 * @param q     the full-text search keyword (required, 1–200 chars)
 * @param type  optional content type filter ({@code null} means no filter) (REQ 9.1)
 * @param sort  sort field; defaults to {@link SortField#SCORE} (REQ 9.3–9.6)
 * @param page  1-based page number (REQ 9.7)
 * @param limit number of results per page, 1–100 (REQ 9.8)
 */
public record SearchCriteria(
        String q,
        ContentType type,
        SortField sort,
        int page,
        int limit
) {
    /**
     * Compact constructor enforcing invariants.
     */
    public SearchCriteria {
        if (q == null || q.isBlank()) {
            throw new IllegalArgumentException("q must not be null or blank");
        }
        if (sort == null) {
            sort = SortField.SCORE;
        }
        if (page < 1) {
            throw new IllegalArgumentException("page must be >= 1");
        }
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
    }
}
