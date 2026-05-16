package com.example.searchengine.web.api;

import com.example.searchengine.application.search.SearchResult;
import com.example.searchengine.application.search.SearchService;

import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for the Search API.
 *
 * <p>Bean Validation rejects malformed query parameters with
 * {@code MethodArgumentNotValidException}/{@code ConstraintViolationException},
 * which the global exception handler translates into HTTP 400 responses with
 * the standard error envelope (REQ 8.3, 8.4, 9.2, 9.9, 14.1, 14.2, 19.1, 19.2).</p>
 *
 * <p>REQ 8.1, 8.3, 8.4, 9.1, 9.2, 9.7, 9.8, 9.9, 9.10, 9.11, 22.4</p>
 */
@RestController
@RequestMapping("/api/v1")
public class SearchController {

    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    /**
     * Executes a keyword search with optional filtering, sorting, and pagination.
     *
     * @param request the validated request DTO
     * @return the paginated search response (REQ 9.10, 9.11)
     */
    @GetMapping(value = "/search", produces = MediaType.APPLICATION_JSON_VALUE)
    public SearchResponse search(@Valid SearchRequest request) {
        SearchResult result = searchService.search(request.toQuery());
        return SearchResponse.from(result);
    }
}
