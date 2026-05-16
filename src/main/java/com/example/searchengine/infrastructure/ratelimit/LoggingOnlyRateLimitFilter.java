package com.example.searchengine.infrastructure.ratelimit;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * "Disabled but tracking" variant of the rate limit filter (Requirement 13.5).
 *
 * <p>Loaded only when {@code ratelimit.enabled=false}. It performs the same per-IP token
 * bucket arithmetic as {@link RateLimitFilter} but always forwards the request down the
 * chain. When a request would have been rejected by the active filter, an {@code INFO}
 * level log entry is emitted naming the client IP, so operators can use real traffic to
 * tune the {@code requests-per-window} and {@code window-seconds} settings before
 * switching enforcement back on.
 */
@Component
@ConditionalOnProperty(prefix = "ratelimit", name = "enabled", havingValue = "false")
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class LoggingOnlyRateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(LoggingOnlyRateLimitFilter.class);

    private final RateLimitProperties properties;
    private final ClientIpResolver clientIpResolver;
    private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public LoggingOnlyRateLimitFilter(RateLimitProperties properties, ClientIpResolver clientIpResolver) {
        this.properties = properties;
        this.clientIpResolver = clientIpResolver;
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
        if (!bucket.tryConsume(1L)) {
            log.info(
                    "Rate limit would have rejected request from clientIp={} (limit={} per {}s, enforcement disabled)",
                    ip,
                    properties.getRequestsPerWindow(),
                    properties.getWindowSeconds());
        }

        // REQ 13.5: never enforce; always pass through.
        chain.doFilter(request, response);
    }

    private boolean isApiRequest(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri != null && uri.startsWith(RateLimitFilter.API_PATH_PREFIX);
    }

    private Bucket newBucket() {
        Bandwidth bandwidth = Bandwidth.builder()
                .capacity(properties.getRequestsPerWindow())
                .refillGreedy(properties.getRequestsPerWindow(), properties.windowDuration())
                .build();
        return Bucket.builder().addLimit(bandwidth).build();
    }
}
