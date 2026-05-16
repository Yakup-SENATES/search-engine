package com.example.searchengine.infrastructure.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * Resolves the client IP address for a servlet request.
 *
 * <p>Honours the {@code X-Forwarded-For} header so that the rate limiter sees the original
 * caller's IP when the application is deployed behind a load balancer or reverse proxy.
 * Falls back to {@link HttpServletRequest#getRemoteAddr()} when the header is absent or blank.
 *
 * <p>If {@code X-Forwarded-For} contains a comma-separated list, the first non-blank entry
 * is used; downstream proxies append themselves to the right of the list, so the leftmost
 * entry is the original client.
 */
@Component
public class ClientIpResolver {

    private static final String X_FORWARDED_FOR = "X-Forwarded-For";

    /**
     * Returns the best-effort client IP for the given request. Never returns {@code null};
     * if neither the header nor {@code getRemoteAddr()} yields a value, the literal string
     * {@code "unknown"} is returned so that bucket lookups remain well-defined.
     */
    public String resolve(HttpServletRequest request) {
        String forwarded = request.getHeader(X_FORWARDED_FOR);
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            String first = (comma >= 0 ? forwarded.substring(0, comma) : forwarded).trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        String remote = request.getRemoteAddr();
        return (remote == null || remote.isBlank()) ? "unknown" : remote;
    }
}
