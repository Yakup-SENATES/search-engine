package com.example.searchengine.infrastructure.provider.jsonprovider;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/**
 * Jackson record representing a single content item in the JSON provider payload.
 *
 * <p>Schema (REQ 2.2):
 * <pre>{@code
 * {
 *   "id": "string",
 *   "title": "string",
 *   "type": "video",
 *   "metrics": { "views": 15000, "likes": 1200, "duration": "PT10M" },
 *   "published_at": "2024-01-15T10:00:00Z",
 *   "tags": ["java", "spring"]
 * }
 * }</pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record JsonContentDto(
        String id,
        String title,
        String type,
        JsonMetrics metrics,
        @JsonProperty("published_at") Instant publishedAt,
        List<String> tags
) {
}
