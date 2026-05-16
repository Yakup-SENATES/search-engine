package com.example.searchengine.domain.content;

/**
 * Sort options for search results.
 *
 * <ul>
 *   <li>{@code SCORE} — order by {@code final_score} descending (REQ 9.3)</li>
 *   <li>{@code POPULARITY} — order by {@code popularity_score} descending (REQ 9.4)</li>
 *   <li>{@code RELEVANCE} — order by full-text search rank descending (REQ 9.5)</li>
 * </ul>
 *
 * <p>{@link #parse(String)} maps {@code null} or blank input to {@code SCORE},
 * satisfying the default-sort requirement (REQ 9.6).</p>
 */
public enum SortField {
    SCORE, POPULARITY, RELEVANCE;

    /**
     * Parses a raw sort string into a {@code SortField}.
     * Returns {@code SCORE} when the input is {@code null} or blank (REQ 9.6).
     *
     * @param raw the user-supplied sort value (case-insensitive)
     * @return the corresponding {@code SortField}
     * @throws IllegalArgumentException if {@code raw} is non-blank and does not
     *         match any known sort field
     */
    public static SortField parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return SCORE;
        }
        return switch (raw.trim().toLowerCase()) {
            case "score" -> SCORE;
            case "popularity" -> POPULARITY;
            case "relevance" -> RELEVANCE;
            default -> throw new IllegalArgumentException("unknown sort field: " + raw);
        };
    }
}
