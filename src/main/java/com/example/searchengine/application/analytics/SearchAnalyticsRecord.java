package com.example.searchengine.application.analytics;

import java.time.Instant;

/**
 * Immutable value object capturing the analytics data for a single search
 * request.
 *
 * <p>Fields map 1-to-1 to the {@code search_analytics} table columns
 * (V2 migration) and to the structured-log fields emitted by the
 * {@code log} sink.</p>
 *
 * <p>REQ 4.1, 4.3</p>
 *
 * @param requestedAt  instant the search request was received (ISO-8601)
 * @param q            the full-text search keyword
 * @param type         optional content type filter (nullable)
 * @param sort         sort field name applied
 * @param page         1-based page number requested
 * @param limit        results per page requested
 * @param totalResults total matching items (nullable on 5xx errors)
 * @param latencyMs    wall-clock latency of the search in milliseconds
 * @param cacheHit     whether the result was served from cache
 * @param requestId    the MDC request-id value
 * @param clientIpHash SHA-256 hex hash of the resolved client IP
 * @param errorCode    standardized error code on 5xx (nullable on success)
 */
public record SearchAnalyticsRecord(
        Instant requestedAt,
        String q,
        String type,
        String sort,
        int page,
        int limit,
        Long totalResults,
        int latencyMs,
        boolean cacheHit,
        String requestId,
        String clientIpHash,
        String errorCode
) {}
