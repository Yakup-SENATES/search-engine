package com.example.searchengine.infrastructure.metrics;

import com.example.searchengine.infrastructure.ratelimit.ClientIpResolver;
import com.example.searchengine.infrastructure.ratelimit.RateLimitFilter;
import com.example.searchengine.infrastructure.ratelimit.RateLimitProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link RateLimitMetrics} backed by a {@link SimpleMeterRegistry}.
 *
 * <p>Validates Requirement 1.7 (operability quick-wins): the {@code path}-tagged
 * counter increments only on rejection, and only the bounded
 * {@link RateLimitFilter#API_PATH_PREFIX} appears as a tag value.</p>
 */
class RateLimitMetricsTest {

    private MeterRegistry registry;
    private RateLimitMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new RateLimitMetrics(registry);
    }

    @Test
    @DisplayName("recordBlocked increments the path-tagged counter (REQ 1.7)")
    void recordBlockedIncrementsCounter() {
        metrics.recordBlocked(RateLimitFilter.API_PATH_PREFIX);
        metrics.recordBlocked(RateLimitFilter.API_PATH_PREFIX);
        metrics.recordBlocked(RateLimitFilter.API_PATH_PREFIX);

        Counter counter = registry.find(RateLimitMetrics.BLOCKED_METER)
                .tag("path", RateLimitFilter.API_PATH_PREFIX).counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(3.0);
    }

    @Test
    @DisplayName("RateLimitFilter increments only on rejection, not on accepted requests (REQ 1.7)")
    void filterIncrementsOnlyOnRejection() throws ServletException, IOException {
        RateLimitProperties props = new RateLimitProperties();
        props.setEnabled(true);
        props.setRequestsPerWindow(2);
        props.setWindowSeconds(60L);

        RateLimitFilter filter = new RateLimitFilter(props, new ClientIpResolver(), metrics);
        FilterChain chain = (req, res) -> { };

        // First 2 requests consume the budget.
        for (int i = 0; i < 2; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/search");
            request.setRemoteAddr("198.51.100.55");
            filter.doFilter(request, new MockHttpServletResponse(), chain);
        }

        Counter counter = registry.find(RateLimitMetrics.BLOCKED_METER)
                .tag("path", RateLimitFilter.API_PATH_PREFIX).counter();
        assertThat(counter == null ? 0.0 : counter.count())
                .as("counter should still be zero after only-accepted requests")
                .isZero();

        // Third request rejected.
        MockHttpServletRequest rejected = new MockHttpServletRequest("GET", "/api/v1/search");
        rejected.setRemoteAddr("198.51.100.55");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(rejected, response, chain);

        assertThat(response.getStatus()).isEqualTo(429);

        Counter counterAfter = registry.find(RateLimitMetrics.BLOCKED_METER)
                .tag("path", RateLimitFilter.API_PATH_PREFIX).counter();
        assertThat(counterAfter).isNotNull();
        assertThat(counterAfter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Non-API requests are not counted, even when the filter sees them (REQ 1.7)")
    void nonApiRequestsDoNotCount() throws ServletException, IOException {
        RateLimitProperties props = new RateLimitProperties();
        props.setEnabled(true);
        props.setRequestsPerWindow(1);
        props.setWindowSeconds(60L);

        RateLimitFilter filter = new RateLimitFilter(props, new ClientIpResolver(), metrics);
        FilterChain chain = (req, res) -> { };

        // /dashboard does not match the API prefix; the filter passes it through
        // without consulting buckets, so it cannot trigger the counter.
        for (int i = 0; i < 5; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/dashboard");
            request.setRemoteAddr("198.51.100.66");
            filter.doFilter(request, new MockHttpServletResponse(), chain);
        }

        Counter counter = registry.find(RateLimitMetrics.BLOCKED_METER)
                .tag("path", RateLimitFilter.API_PATH_PREFIX).counter();
        assertThat(counter == null ? 0.0 : counter.count()).isZero();
    }
}
