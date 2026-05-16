package com.example.searchengine.domain.content;

import java.util.Optional;
import java.util.UUID;

/**
 * Port (domain interface) for content persistence and search.
 * Pure Java — no JPA or framework imports. The infrastructure layer provides
 * the concrete adapter.
 *
 * <p>REQ 5.1, 5.2, 5.5, 8.2, 9.1, 9.3–9.6, 22.3</p>
 */
public interface ContentRepository {

    /**
     * Inserts the content if {@code (provider, externalId)} is new, otherwise
     * updates the existing row. Implemented as a single SQL upsert in PostgreSQL
     * via {@code INSERT ... ON CONFLICT (provider, external_id) DO UPDATE} (REQ 5.2).
     *
     * @param content the content to persist
     * @return {@link UpsertOutcome#INSERTED} or {@link UpsertOutcome#UPDATED} (REQ 5.7)
     */
    UpsertOutcome upsert(Content content);

    /**
     * Full-text search against the {@code title || description} tsvector (REQ 5.5, 8.2),
     * filtered, sorted, and paginated according to the given criteria. The
     * implementation uses parameterized queries — no string concatenation of
     * user input (REQ 19.3).
     *
     * @param criteria the search parameters
     * @return a page of matching content with total count (REQ 9.10)
     */
    SearchPage search(SearchCriteria criteria);

    /**
     * Returns the top {@code limit} content items, optionally filtered by
     * {@code type}, ordered by the supplied {@link SortField} with deterministic
     * tie-break by {@code id ASC}. Used by the dashboard for the default "top N
     * by score" view where no full-text query is supplied (REQ 10.3).
     *
     * <p>The {@code RELEVANCE} sort falls back to {@code relevance_score} on the
     * stored row (no full-text query is computed) since there is no keyword to
     * rank against.</p>
     *
     * @param sort  sort field; non-null
     * @param type  optional content type filter (null means no filter) (REQ 10.6)
     * @param limit maximum number of rows to return (1–100)
     * @return a page of matching content with total count
     */
    SearchPage listTop(SortField sort, ContentType type, int limit);

    /**
     * Finds a content item by its internal UUID.
     *
     * @param id the content UUID
     * @return the content if found, empty otherwise
     */
    Optional<Content> findById(UUID id);
}
