package com.example.searchengine.web.dashboard;

import com.example.searchengine.application.search.SearchResult;
import com.example.searchengine.domain.content.Content;

import java.util.List;

/**
 * Lightweight view DTO for one row of the dashboard table.
 * Holds exactly the three columns rendered by the dashboard
 * template ({@code Title | Type | Score}, REQ 10.2).
 *
 * <p>The static {@link #fromResult(SearchResult)} factory performs the
 * {@code Content → DashboardRow} mapping here, so the controller never
 * touches the domain {@code Content} class directly. This keeps the
 * controller compliant with the architecture rule that controllers must
 * not depend on the {@code Content} aggregate (REQ 22.5).</p>
 *
 * @param title the content title
 * @param type  the content type label (lower-case: {@code "video"} or
 *              {@code "text"})
 * @param score the {@code final_score} value used for ordering
 */
public record DashboardRow(String title, String type, double score) {

    /**
     * Maps each {@link Content} item in the supplied {@link SearchResult} to a
     * {@code DashboardRow}, preserving the order returned by the repository
     * (which already applies the deterministic {@code id ASC} tie-break, REQ 10.3).
     *
     * @param result the search result returned by the application service
     * @return an immutable list of dashboard rows, never null
     */
    public static List<DashboardRow> fromResult(SearchResult result) {
        return result.items().stream()
                .map(DashboardRow::fromContent)
                .toList();
    }

    private static DashboardRow fromContent(Content content) {
        return new DashboardRow(
                content.title(),
                content.type().name().toLowerCase(),
                content.finalScore()
        );
    }
}
