package com.example.searchengine.infrastructure.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Servlet filter that propagates a per-request correlation id through SLF4J MDC.
 *
 * <p>For every HTTP request the filter:
 * <ul>
 *   <li>reads the {@code X-Request-Id} request header, or generates a fresh UUID
 *       if it is missing or blank;</li>
 *   <li>places the value into the SLF4J MDC under key {@value #MDC_KEY} so it can
 *       be emitted alongside every log line during the request;</li>
 *   <li>mirrors the value back to the client via the {@code X-Request-Id}
 *       response header;</li>
 *   <li>always removes the MDC entry in a {@code finally} block so that the
 *       value cannot leak across pooled threads.</li>
 * </ul>
 *
 * <p>Registered with {@link org.springframework.core.Ordered#HIGHEST_PRECEDENCE}
 * so that subsequent filters and controllers see the id in MDC.
 *
 * @see <a href="REQ 16.1, 16.2">Requirements 16.1, 16.2</a>
 */
public class RequestIdFilter extends OncePerRequestFilter {

    /** MDC key under which the request id is stored. */
    public static final String MDC_KEY = "requestId";

    /** Name of the request and response header that carries the request id. */
    public static final String HEADER_NAME = "X-Request-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestId = resolveRequestId(request.getHeader(HEADER_NAME));
        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER_NAME, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    private static String resolveRequestId(String headerValue) {
        if (headerValue == null) {
            return UUID.randomUUID().toString();
        }
        String trimmed = headerValue.trim();
        return trimmed.isEmpty() ? UUID.randomUUID().toString() : trimmed;
    }
}
