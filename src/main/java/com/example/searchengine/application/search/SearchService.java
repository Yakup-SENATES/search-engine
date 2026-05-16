package com.example.searchengine.application.search;

/**
 * Application-layer service for executing search queries.
 *
 * <p>The caller (controller) is responsible for input validation (REQ 19.1);
 * this service trusts its input shape but still escapes FTS metacharacters
 * (REQ 8.5) via the repository's parameterized query.</p>
 *
 * <p>Caching: the {@code @Cacheable} annotation on the implementation keys on
 * the 5-tuple {@code (q, type, sort, page, limit)} into the "search" cache
 * region (REQ 12.1). Cache backend failure falls through transparently
 * (REQ 12.7).</p>
 *
 * <p>REQ 8.2, 8.5, 9.1, 9.3–9.8, 12.1</p>
 */
public interface SearchService {

    /**
     * Executes a validated search query against the content repository.
     *
     * @param query the search parameters
     * @return the paginated search result, never null
     */
    SearchResult search(SearchQuery query);

    /**
     * Returns the top {@code limit} content items, optionally filtered by
     * {@code type}, ordered by the supplied {@code sort} value with
     * deterministic tie-break by {@code id ASC} (REQ 10.3).
     *
     * <p>This path bypasses the full-text query so it can be used by the
     * dashboard, where no keyword is supplied. The semantics of {@code sort}
     * and {@code type} match the {@link #search(SearchQuery)} method (REQ 10.4).</p>
     *
     * @param sort  sort field name ({@code score}, {@code popularity},
     *              {@code relevance}); {@code null}/blank defaults to
     *              {@code score} via {@link com.example.searchengine.domain.content.SortField#parse(String)}
     * @param type  optional type filter ({@code video}, {@code text});
     *              {@code null}/blank means no filter
     * @param limit number of rows to return (1–100)
     * @return the page of top items, never null
     */
    SearchResult listTop(String sort, String type, int limit);
}
