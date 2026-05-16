package com.example.searchengine.web.error;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Servlet filter enforcing the maximum request size budget mandated by REQ 19.5.
 *
 * <p>For every HTTP request the filter computes
 * {@code total = max(getContentLength(), 0) + utf8Bytes(getQueryString())}. When
 * {@code total} is greater than or equal to {@value #MAX_REQUEST_SIZE_BYTES_EXCLUSIVE}
 * (i.e. the combined payload exceeds the {@value #MAX_REQUEST_SIZE_BYTES} byte
 * budget), the filter short-circuits the chain with HTTP 413 and the standard
 * error envelope {@code {"error":{"code":"PAYLOAD_TOO_LARGE","message":...}}}
 * (REQ 14.1, 19.5). The downstream chain — including the {@code SearchService} —
 * is never invoked for an over-sized request.
 *
 * <p>The header-driven {@link HttpServletRequest#getContentLength()} returns
 * {@code -1} when the {@code Content-Length} header is absent or unparseable;
 * we treat that as zero so requests without a body (typical for {@code GET})
 * are evaluated solely on the query-string size.
 *
 * <p>The filter is registered with {@link Order#value() @Order(HIGHEST_PRECEDENCE + 50)}
 * so it runs after the request-id filter (which seeds the MDC) but before any
 * authentication, validation, or rate-limit filters that might consume request
 * resources.
 *
 * @see <a href="REQ 19.5, 14.1">Requirements 19.5, 14.1</a>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 50)
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    /** Maximum permitted total request size in bytes (REQ 19.5). */
    public static final int MAX_REQUEST_SIZE_BYTES = 8192;

    /**
     * Threshold at which a request is rejected (i.e. {@code total >=} this value
     * is rejected, mirroring REQ 19.5: "greater than or equal to 8193").
     */
    public static final int MAX_REQUEST_SIZE_BYTES_EXCLUSIVE = MAX_REQUEST_SIZE_BYTES + 1;

    /** Body emitted on rejection. Matches the error envelope mandated by REQ 14.1. */
    static final String PAYLOAD_TOO_LARGE_BODY =
            "{\"error\":{\"code\":\"PAYLOAD_TOO_LARGE\",\"message\":\"Request payload too large\"}}";

    private static final Logger log = LoggerFactory.getLogger(RequestSizeLimitFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long total = computeTotalRequestSize(request);
        if (total >= MAX_REQUEST_SIZE_BYTES_EXCLUSIVE) {
            log.warn("rejecting oversized request method={} uri={} totalBytes={}",
                    request.getMethod(), request.getRequestURI(), total);
            writeRejection(response);
            return;
        }
        filterChain.doFilter(request, response);
    }

    /**
     * Computes the total request size in bytes as the sum of the declared
     * {@code Content-Length} (or zero when absent) and the UTF-8 byte length
     * of the query string (or zero when absent).
     */
    static long computeTotalRequestSize(HttpServletRequest request) {
        long bodyBytes = Math.max(0, (long) request.getContentLength());
        String queryString = request.getQueryString();
        long queryBytes = queryString == null
                ? 0L
                : queryString.getBytes(StandardCharsets.UTF_8).length;
        return bodyBytes + queryBytes;
    }

    private static void writeRejection(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(PAYLOAD_TOO_LARGE_BODY);
    }
}
