package com.example.searchengine.web.dashboard;

import com.example.searchengine.application.search.SearchResult;
import com.example.searchengine.application.search.SearchService;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Set;

/**
 * Server-rendered dashboard listing the top aggregated content (REQ 10).
 *
 * <p>Defaults: top 20 rows by {@code final_score DESC} with deterministic
 * tie-break by {@code id ASC} (REQ 10.3). The view always renders exactly
 * three columns — {@code Title | Type | Score} — and shows an empty-state
 * message when no rows match (REQ 10.2, 10.8).</p>
 *
 * <p>Tolerant parsing: invalid {@code sort} or {@code type} values fall back
 * to the defaults and surface a visible "ignored" notice (REQ 10.5, 10.7).
 * The controller reuses {@link SearchService#listTop(String, String, int)} so
 * filtering and ordering match the API (REQ 10.4, 10.6).</p>
 */
@Controller
public class DashboardController {

    static final int DEFAULT_LIMIT = 20;

    private static final Set<String> ALLOWED_SORTS = Set.of("score", "popularity", "relevance");
    private static final Set<String> ALLOWED_TYPES = Set.of("video", "text");

    private final SearchService searchService;

    public DashboardController(SearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping("/dashboard")
    public String dashboard(
            @RequestParam(name = "sort", required = false) String sort,
            @RequestParam(name = "type", required = false) String type,
            Model model
    ) {
        IgnoredParamNotice.Builder noticeBuilder = new IgnoredParamNotice.Builder();

        // Tolerant sort parsing — invalid values fall back to "score" (REQ 10.5).
        String effectiveSort = parseSortOrNotify(sort, noticeBuilder);

        // Tolerant type parsing — invalid values fall back to no filter (REQ 10.7).
        String effectiveType = parseTypeOrNotify(type, noticeBuilder);

        SearchResult result = searchService.listTop(effectiveSort, effectiveType, DEFAULT_LIMIT);

        List<DashboardRow> rows = DashboardRow.fromResult(result);

        model.addAttribute("rows", rows);
        model.addAttribute("notice", noticeBuilder.build());
        model.addAttribute("empty", rows.isEmpty()); // REQ 10.8
        return "dashboard";
    }

    /**
     * Tolerantly normalizes the {@code sort} parameter. Any non-allowed value
     * falls back to {@code "score"} and adds an "ignored" notice (REQ 10.5).
     * {@code null} or blank input is treated as the default and is NOT an
     * ignored value.
     */
    private static String parseSortOrNotify(String raw, IgnoredParamNotice.Builder notice) {
        if (raw == null || raw.isBlank()) {
            return "score";
        }
        String normalized = raw.trim().toLowerCase();
        if (ALLOWED_SORTS.contains(normalized)) {
            return normalized;
        }
        notice.ignored("sort", raw);
        return "score";
    }

    /**
     * Tolerantly normalizes the {@code type} parameter. Any non-allowed value
     * falls back to "no filter" and adds an "ignored" notice (REQ 10.7).
     * {@code null} or blank input is treated as the default and is NOT an
     * ignored value.
     */
    private static String parseTypeOrNotify(String raw, IgnoredParamNotice.Builder notice) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim().toLowerCase();
        if (ALLOWED_TYPES.contains(normalized)) {
            return normalized;
        }
        notice.ignored("type", raw);
        return null;
    }
}
