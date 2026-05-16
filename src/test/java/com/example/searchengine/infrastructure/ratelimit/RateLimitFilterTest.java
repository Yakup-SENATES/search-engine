package com.example.searchengine.infrastructure.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Unit tests for {@link RateLimitFilter}.
 *
 * <p>Validates Requirements 13.1, 13.2, 13.3, 13.4 and 14.1: paths under {@code /api/v1/**}
 * are rate-limited per client IP, the (configurable) per-window budget is enforced,
 * rejected requests carry HTTP 429, a numeric {@code Retry-After} header, and the standard
 * error envelope.
 */
class RateLimitFilterTest {

    private static final String API_PATH = "/api/v1/search";

    private RateLimitProperties propertiesWith(int requestsPerWindow, long windowSeconds) {
        RateLimitProperties props = new RateLimitProperties();
        props.setEnabled(true);
        props.setRequestsPerWindow(requestsPerWindow);
        props.setWindowSeconds(windowSeconds);
        return props;
    }

    private RateLimitFilter newFilter(RateLimitProperties props) {
        return new RateLimitFilter(props, new ClientIpResolver());
    }

    @Test
    void allowsRequestsUnderLimitForSameClient() throws ServletException, IOException {
        RateLimitFilter filter = newFilter(propertiesWith(3, 60L));
        AtomicInteger downstream = new AtomicInteger();

        for (int i = 0; i < 3; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", API_PATH);
            request.setRemoteAddr("198.51.100.10");
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain chain = (req, res) -> downstream.incrementAndGet();

            filter.doFilter(request, response, chain);

            assertThat(response.getStatus()).isEqualTo(200);
        }

        assertThat(downstream.get()).isEqualTo(3);
    }

    @Test
    void rejectsRequestOverLimitWith429AndStandardEnvelope() throws ServletException, IOException {
        RateLimitFilter filter = newFilter(propertiesWith(2, 60L));
        AtomicInteger downstream = new AtomicInteger();
        FilterChain chain = (req, res) -> downstream.incrementAndGet();

        // Consume the budget.
        for (int i = 0; i < 2; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", API_PATH);
            request.setRemoteAddr("198.51.100.20");
            filter.doFilter(request, new MockHttpServletResponse(), chain);
        }

        // Next request must be rejected.
        MockHttpServletRequest rejected = new MockHttpServletRequest("GET", API_PATH);
        rejected.setRemoteAddr("198.51.100.20");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(rejected, response, chain);

        assertThat(downstream.get()).isEqualTo(2);
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentType()).contains("application/json");

        String retryAfter = response.getHeader("Retry-After");
        assertThat(retryAfter).isNotNull();
        long retrySeconds = Long.parseLong(retryAfter);
        assertThat(retrySeconds).isBetween(1L, 60L);

        assertThat(response.getContentAsString()).contains("\"code\":\"RATE_LIMITED\"");
        assertThat(response.getContentAsString()).contains("\"error\"");
    }

    @Test
    void tracksBucketsPerClientIp() throws ServletException, IOException {
        RateLimitFilter filter = newFilter(propertiesWith(1, 60L));
        FilterChain chain = (req, res) -> {};

        // Client A consumes its only token.
        MockHttpServletRequest a1 = new MockHttpServletRequest("GET", API_PATH);
        a1.setRemoteAddr("203.0.113.1");
        MockHttpServletResponse aResp1 = new MockHttpServletResponse();
        filter.doFilter(a1, aResp1, chain);
        assertThat(aResp1.getStatus()).isEqualTo(200);

        // Client B should not be affected by Client A's bucket.
        MockHttpServletRequest b1 = new MockHttpServletRequest("GET", API_PATH);
        b1.setRemoteAddr("203.0.113.2");
        MockHttpServletResponse bResp1 = new MockHttpServletResponse();
        filter.doFilter(b1, bResp1, chain);
        assertThat(bResp1.getStatus()).isEqualTo(200);

        // Client A is over budget.
        MockHttpServletRequest a2 = new MockHttpServletRequest("GET", API_PATH);
        a2.setRemoteAddr("203.0.113.1");
        MockHttpServletResponse aResp2 = new MockHttpServletResponse();
        filter.doFilter(a2, aResp2, chain);
        assertThat(aResp2.getStatus()).isEqualTo(429);
    }

    @Test
    void usesXForwardedForToIdentifyClient() throws ServletException, IOException {
        RateLimitFilter filter = newFilter(propertiesWith(1, 60L));
        FilterChain chain = (req, res) -> {};

        // Two requests from the same X-Forwarded-For but different remote addresses
        // must share the same bucket.
        MockHttpServletRequest first = new MockHttpServletRequest("GET", API_PATH);
        first.setRemoteAddr("10.0.0.1");
        first.addHeader("X-Forwarded-For", "203.0.113.99");
        filter.doFilter(first, new MockHttpServletResponse(), chain);

        MockHttpServletRequest second = new MockHttpServletRequest("GET", API_PATH);
        second.setRemoteAddr("10.0.0.2");
        second.addHeader("X-Forwarded-For", "203.0.113.99");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(second, response, chain);

        assertThat(response.getStatus()).isEqualTo(429);
    }

    @Test
    void doesNotApplyToNonApiPaths() throws ServletException, IOException {
        RateLimitFilter filter = newFilter(propertiesWith(1, 60L));
        AtomicInteger downstream = new AtomicInteger();
        FilterChain chain = (req, res) -> downstream.incrementAndGet();

        for (int i = 0; i < 5; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/dashboard");
            request.setRemoteAddr("198.51.100.30");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, chain);
            assertThat(response.getStatus()).isEqualTo(200);
        }

        assertThat(downstream.get()).isEqualTo(5);
    }

    @Test
    void retryAfterIsCappedByWindowSeconds() throws ServletException, IOException {
        RateLimitProperties props = propertiesWith(1, 60L);
        RateLimitFilter filter = newFilter(props);
        FilterChain chain = (req, res) -> {};

        MockHttpServletRequest first = new MockHttpServletRequest("GET", API_PATH);
        first.setRemoteAddr("198.51.100.40");
        filter.doFilter(first, new MockHttpServletResponse(), chain);

        MockHttpServletRequest rejected = new MockHttpServletRequest("GET", API_PATH);
        rejected.setRemoteAddr("198.51.100.40");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(rejected, response, chain);

        long retrySeconds = Long.parseLong(response.getHeader("Retry-After"));
        assertThat(retrySeconds).isBetween(1L, props.getWindowSeconds());
    }
}
