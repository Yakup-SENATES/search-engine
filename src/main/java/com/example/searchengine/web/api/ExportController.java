package com.example.searchengine.web.api;

import com.example.searchengine.application.search.SearchQuery;
import com.example.searchengine.application.search.SearchResult;
import com.example.searchengine.application.search.SearchService;
import com.example.searchengine.domain.content.Content;
import com.example.searchengine.infrastructure.config.ExportProperties;
import com.example.searchengine.web.api.csv.Csv;
import com.example.searchengine.web.error.ErrorResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * REST controller for exporting search results as CSV or JSON downloads.
 *
 * <p>Both endpoints reuse {@link SearchService#search(SearchQuery)} so the
 * existing cache and pagination logic apply unchanged (REQ 5.1–5.6).</p>
 *
 * <p>The {@code limit} parameter defaults to 100 when omitted (REQ 5.3).
 * When {@code limit} exceeds the configured {@code export.search.max-rows}
 * (default 1000), the endpoint returns HTTP 400 with the standardized
 * {@code INVALID_QUERY} error envelope (REQ 5.3).</p>
 */
@RestController
@RequestMapping("/api/v1")
public class ExportController {

    private static final int DEFAULT_EXPORT_LIMIT = 100;
    private static final String DEFAULT_SORT = "score";

    /** UTF-8 BOM bytes so Excel opens the CSV correctly. */
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    /** CSV header row. */
    private static final String CSV_HEADER = "id,title,type,score,publishedAt";

    /** Filename timestamp format: yyyyMMdd-HHmmss. */
    private static final DateTimeFormatter FILENAME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final SearchService searchService;
    private final ExportProperties exportProperties;

    public ExportController(SearchService searchService, ExportProperties exportProperties) {
        this.searchService = searchService;
        this.exportProperties = exportProperties;
    }

    /**
     * Exports search results as a CSV file with UTF-8 BOM prefix.
     *
     * <p>Response uses {@link StreamingResponseBody} for constant-memory streaming
     * regardless of result size.</p>
     *
     * @param q     search keyword (required)
     * @param type  optional content type filter
     * @param sort  optional sort field
     * @param limit number of results (defaults to 100)
     * @return streaming CSV response with appropriate headers, or 400 if limit exceeds max-rows
     */
    @GetMapping("/search.csv")
    public ResponseEntity<?> exportCsv(
            @RequestParam String q,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) Integer limit) {

        int resolvedLimit = limit != null ? limit : DEFAULT_EXPORT_LIMIT;
        String resolvedSort = sort != null && !sort.isBlank() ? sort : DEFAULT_SORT;

        ResponseEntity<ErrorResponse> limitError = validateLimit(resolvedLimit);
        if (limitError != null) {
            return limitError;
        }

        SearchQuery query = new SearchQuery(q, type, resolvedSort, 1, resolvedLimit);
        SearchResult result = searchService.search(query);

        String filename = "search-" + FILENAME_FORMATTER.format(Instant.now()) + ".csv";

        StreamingResponseBody body = outputStream -> {
            outputStream.write(UTF8_BOM);
            Writer writer = new OutputStreamWriter(outputStream, StandardCharsets.UTF_8);
            writer.write(CSV_HEADER + "\r\n");

            for (Content item : result.items()) {
                String row = Csv.formatRow(
                        item.id().toString(),
                        item.title(),
                        item.type().name().toLowerCase(),
                        String.format("%.1f", item.finalScore()),
                        item.publishedAt().toString()
                );
                writer.write(row);
            }
            writer.flush();
        };

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filename + "\"")
                .body(body);
    }

    /**
     * Exports search results as a JSON array download.
     *
     * <p>The JSON array uses the same schema as {@code data[]} from the existing
     * {@link SearchResponse}, with the addition of {@code publishedAt}.</p>
     *
     * @param q     search keyword (required)
     * @param type  optional content type filter
     * @param sort  optional sort field
     * @param limit number of results (defaults to 100)
     * @return JSON array response with appropriate headers, or 400 if limit exceeds max-rows
     */
    @GetMapping("/search.json")
    public ResponseEntity<?> exportJson(
            @RequestParam String q,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) Integer limit) {

        int resolvedLimit = limit != null ? limit : DEFAULT_EXPORT_LIMIT;
        String resolvedSort = sort != null && !sort.isBlank() ? sort : DEFAULT_SORT;

        ResponseEntity<ErrorResponse> limitError = validateLimit(resolvedLimit);
        if (limitError != null) {
            return limitError;
        }

        SearchQuery query = new SearchQuery(q, type, resolvedSort, 1, resolvedLimit);
        SearchResult result = searchService.search(query);

        String filename = "search-" + FILENAME_FORMATTER.format(Instant.now()) + ".json";

        List<ExportItemDto> items = result.items().stream()
                .map(ExportController::toExportItem)
                .toList();

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "application/json; charset=UTF-8")
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filename + "\"")
                .body(items);
    }

    // ------------------------------------------------------------------------
    // Validation
    // ------------------------------------------------------------------------

    /**
     * Validates that the resolved limit does not exceed the configured maximum rows.
     *
     * @param resolvedLimit the effective limit value
     * @return a 400 error response if limit exceeds max-rows, or {@code null} if valid
     */
    private ResponseEntity<ErrorResponse> validateLimit(int resolvedLimit) {
        int maxRows = exportProperties.getMaxRows();
        if (resolvedLimit > maxRows) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ErrorResponse.of("INVALID_QUERY",
                            "limit exceeds maximum allowed rows (" + maxRows + ")"));
        }
        return null;
    }

    // ------------------------------------------------------------------------
    // Mapping
    // ------------------------------------------------------------------------

    private static ExportItemDto toExportItem(Content content) {
        return new ExportItemDto(
                content.id().toString(),
                content.title(),
                content.type().name().toLowerCase(),
                content.finalScore(),
                content.publishedAt().toString()
        );
    }
}
