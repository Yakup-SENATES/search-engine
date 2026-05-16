package com.example.searchengine.web.api;

/**
 * Pagination metadata returned alongside search results.
 *
 * <p>REQ 9.10, 9.11</p>
 *
 * @param page  the 1-based page number returned (REQ 9.7)
 * @param limit the page size (REQ 9.8)
 * @param total the total number of matching items across all pages (REQ 9.10, 9.11)
 */
public record PaginationDto(int page, int limit, long total) {
}
