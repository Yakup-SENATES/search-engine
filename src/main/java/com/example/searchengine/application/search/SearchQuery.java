package com.example.searchengine.application.search;

/**
 * Input record for the search use-case, carrying the validated parameters
 * from the web layer.
 *
 * <p>REQ 8.1, 9.1, 9.3–9.8</p>
 *
 * @param q     the full-text search keyword (required, 1–200 chars)
 * @param type  optional content type filter (null means no filter)
 * @param sort  optional sort field name (null/blank defaults to "score")
 * @param page  1-based page number (default 1)
 * @param limit results per page, 1–100 (default 10)
 */
public record SearchQuery(
        String q,
        String type,
        String sort,
        int page,
        int limit
) {}
