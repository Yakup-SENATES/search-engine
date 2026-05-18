package com.example.searchengine.infrastructure.ratelimit;

import com.example.searchengine.infrastructure.metrics.RateLimitMetrics;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Bucket4j-backed rate limit filter (Requirements 13.1–13.4, 14.1).
 *
 * <p>Tracks a token bucket per client IP in a {@link ConcurrentHashMap}. Each request
 * consumes one token. When the bucket is empty the filter responds with HTTP 429, a
 * {@code Retry-After} header carrying the number of seconds until the next token is
 * available, and the standard error envelope
 * {@code {"error":{"code":"RATE_LIMITED","message":"Rate limit exceeded"}}}.
 *
 * <p>Loaded by default; when {@code ratelimit.enabled=false} this bean is replaced by
 * {@link LoggingOnlyRateLimitFilter}, which preserves the same bucket arithmetic for
 * tuning data without enforcement (REQ 13.5).
 *
 * <p>Only requests whose URI begins with {@code /api/v1/} are subject to enforcement
 * (REQ 13.1); every other request is forwarded down the chain unchanged.
 */
@Component
@ConditionalOnProperty(prefix = "ratelimit", name = "enabled", havingValue = "true", matchIfMissing = true)
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class RateLimitFilter extends OncePerRequestFilter {

    /** REQ 13.1: only paths under {@code /api/v1/} are subject to rate limiting. */
    public static final String API_PATH_PREFIX = "/api/v1/";

    /** Body emitted on rejection. Matches the error envelope mandated by REQ 14.1. */
    static final String RATE_LIMITED_BODY =
            "{\"error\":{\"code\":\"RATE_LIMITED\",\"message\":\"Rate limit exceeded\"}}";

    private final RateLimitProperties properties;
    private final ClientIpResolver clientIpResolver;
    private final RateLimitMetrics rateLimitMetrics;
    private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimitFilter(RateLimitProperties properties,
                           ClientIpResolver clientIpResolver,
                           RateLimitMetrics rateLimitMetrics) {
        this.properties = properties;
        this.clientIpResolver = clientIpResolver;
        this.rateLimitMetrics = rateLimitMetrics;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!isApiRequest(request)) {
            chain.doFilter(request, response);
            return;
        }

        String ip = clientIpResolver.resolve(request);
        Bucket bucket = buckets.computeIfAbsent(ip, key -> newBucket());
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1L);

        if (probe.isConsumed()) {
            chain.doFilter(request, response);
            return;
        }

        long retryAfterSeconds = TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()) + 1L;
        if (retryAfterSeconds < 1L) {
            retryAfterSeconds = 1L;
        }
        if (retryAfterSeconds > properties.getWindowSeconds()) {
            retryAfterSeconds = properties.getWindowSeconds();
        }

        rateLimitMetrics.recordBlocked(API_PATH_PREFIX);

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(RATE_LIMITED_BODY);
    }

    private boolean isApiRequest(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri != null && uri.startsWith(API_PATH_PREFIX);
    }

    private Bucket newBucket() {
        Bandwidth bandwidth = Bandwidth.builder()
                .capacity(properties.getRequestsPerWindow())
                .refillGreedy(properties.getRequestsPerWindow(), properties.windowDuration())
                .build();
        return Bucket.builder().addLimit(bandwidth).build();
    }
}
