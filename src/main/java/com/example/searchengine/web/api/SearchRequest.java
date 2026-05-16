package com.example.searchengine.web.api;

import com.example.searchengine.application.search.SearchQuery;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request DTO for {@code GET /api/v1/search}, populated from query parameters
 * by Spring's {@code @ModelAttribute} binding and validated via Bean Validation.
 *
 * <p>Constraint violations are translated to HTTP 400 with the standard error
 * envelope by the global exception handler (REQ 8.3, 8.4, 9.2, 9.9, 14.1, 14.2,
 * 19.1, 19.2).</p>
 *
 * <p>REQ 8.1, 8.3, 8.4, 9.1, 9.2, 9.7, 9.8, 9.9, 19.1, 19.2, 22.4, 22.5</p>
 *
 * @param q     full-text search keyword, required, 1–200 chars (REQ 8.1, 8.3, 8.4)
 * @param type  optional content type filter ({@code video} or {@code text}) (REQ 9.1, 9.2)
 * @param sort  optional sort field ({@code score}, {@code popularity}, {@code relevance}) (REQ 9.3–9.6)
 * @param page  optional 1-based page number; defaults to 1 (REQ 9.7, 9.9)
 * @param limit optional page size, 1–100; defaults to 10 (REQ 9.8, 9.9)
 */
public record SearchRequest(
        @NotBlank
        @Size(min = 1, max = 200)
        String q,

        @Pattern(regexp = "^(video|text)$")
        String type,

        @Pattern(regexp = "^(score|popularity|relevance)$")
        String sort,

        @Min(1)
        Integer page,

        @Min(1) @Max(100)
        Integer limit
) {
    /** Default page number applied when {@code page} is absent (REQ 9.7). */
    public static final int DEFAULT_PAGE = 1;

    /** Default page size applied when {@code limit} is absent (REQ 9.8). */
    public static final int DEFAULT_LIMIT = 10;

    /** Default sort token applied when {@code sort} is absent (REQ 9.6). */
    public static final String DEFAULT_SORT = "score";

    /**
     * Converts this validated request into the application-layer
     * {@link SearchQuery}, applying defaults for absent optional parameters
     * (REQ 9.6, 9.7, 9.8).
     *
     * @return a {@code SearchQuery} ready to hand to {@code SearchService}
     */
    public SearchQuery toQuery() {
        return new SearchQuery(
                q,
                type,
                sort == null || sort.isBlank() ? DEFAULT_SORT : sort,
                page == null ? DEFAULT_PAGE : page,
                limit == null ? DEFAULT_LIMIT : limit
        );
    }
}
