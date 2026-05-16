package com.example.searchengine.infrastructure.provider.jsonprovider;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Jackson record representing the nested {@code metrics} object in the JSON provider payload.
 *
 * <p>Schema: {@code { "views": 15000, "likes": 1200, "duration": "PT10M" }}</p>
 *
 * @param views    view count metric
 * @param likes    like count metric
 * @param duration ISO-8601 duration string (e.g. "PT10M")
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record JsonMetrics(
        long views,
        long likes,
        String duration
) {
}
