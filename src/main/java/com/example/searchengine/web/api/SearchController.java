package com.example.searchengine.web.api;

import com.example.searchengine.application.analytics.SearchAnalyticsRecord;
import com.example.searchengine.application.analytics.SearchAnalyticsRecorder;
import com.example.searchengine.application.search.SearchResult;
import com.example.searchengine.application.search.SearchService;
import com.example.searchengine.infrastructure.admin.ClientIpHasher;
import com.example.searchengine.infrastructure.ratelimit.ClientIpResolver;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * REST controller for the Search API.
 *
 * <p>Bean Validation rejects malformed query parameters with
 * {@code MethodArgumentNotValidException}/{@code ConstraintViolationException},
 * which the global exception handler translates into HTTP 400 responses with
 * the standard error envelope (REQ 8.3, 8.4, 9.2, 9.9, 14.1, 14.2, 19.1, 19.2).</p>
 *
 * <p>After a successful search, a {@link SearchAnalyticsRecord} is recorded
 * via {@link SearchAnalyticsRecorder} (best-effort, fire-and-forget per REQ 4.4).</p>
 *
 * <p>REQ 4.1, 8.1, 8.3, 8.4, 9.1, 9.2, 9.7, 9.8, 9.9, 9.10, 9.11, 22.4</p>
 */
@RestController
@RequestMapping("/api/v1")
public class SearchController {

    private final SearchService searchService;
    private final SearchAnalyticsRecorder analyticsRecorder;
    private final ClientIpHasher clientIpHasher;
    private final ClientIpResolver clientIpResolver;

    public SearchController(SearchService searchService,
                            SearchAnalyticsRecorder analyticsRecorder,
                            ClientIpHasher clientIpHasher,
                            ClientIpResolver clientIpResolver) {
        this.searchService = searchService;
        this.analyticsRecorder = analyticsRecorder;
        this.clientIpHasher = clientIpHasher;
        this.clientIpResolver = clientIpResolver;
    }

    /**
     * Executes a keyword search with optional filtering, sorting, and pagination.
     *
     * @param request        the validated request DTO
     * @param servletRequest the raw HTTP request (for IP resolution)
     * @return the paginated search response (REQ 9.10, 9.11)
     */
    @GetMapping(value = "/search", produces = MediaType.APPLICATION_JSON_VALUE)
    public SearchResponse search(@Valid SearchRequest request, HttpServletRequest servletRequest) {
        Instant requestedAt = Instant.now();
        long startNanos = System.nanoTime();

        SearchResult result = searchService.search(request.toQuery());
        SearchResponse response = SearchResponse.from(result);

        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;

        // Record analytics (best-effort, fire-and-forget)
        SearchAnalyticsRecord record = new SearchAnalyticsRecord(
                requestedAt,
                request.q(),
                request.type(),
                request.sort() == null || request.sort().isBlank() ? SearchRequest.DEFAULT_SORT : request.sort(),
                request.page() == null ? SearchRequest.DEFAULT_PAGE : request.page(),
                request.limit() == null ? SearchRequest.DEFAULT_LIMIT : request.limit(),
                result.total(),
                (int) latencyMs,
                result.cacheHit(),
                MDC.get("requestId"),
                clientIpHasher.hash(clientIpResolver.resolve(servletRequest)),
                null // errorCode is null on success
        );
        analyticsRecorder.record(record);

        return response;
    }
}
