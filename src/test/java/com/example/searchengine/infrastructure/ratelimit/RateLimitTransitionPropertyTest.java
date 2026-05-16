package com.example.searchengine.infrastructure.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.io.IOException;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Property-based test for rate-limit transition correctness.
 *
 * <p><b>Validates: Requirements 13.2, 13.3, 14.1</b></p>
 *
 * <p>For any client IP and any monotonically increasing sequence of {@code N}
 * requests issued within a single {@code window-seconds} window, the first
 * {@code requests-per-window} requests receive HTTP responses other than 429,
 * every request beyond the limit receives HTTP 429 with a {@code Retry-After}
 * header whose integer value is &ge; 1 and &le; {@code window-seconds}, and
 * the body matches the standard error envelope.</p>
 */
class RateLimitTransitionPropertyTest {

    private static final String API_PATH = "/api/v1/search";

    /**
     * Drives a freshly constructed {@link RateLimitFilter} with {@code N} requests from
     * a single client IP, all issued within one {@code windowSeconds} window. Asserts
     * the transition between accepted and 429-rejected responses follows the property.
     */
    @Property(tries = 100)
    @Label("Feature: search-engine-service, Property 10: Rate-limit transition correctness")
    void rateLimitTransitionCorrectness(@ForAll("scenarios") Scenario scenario)
            throws ServletException, IOException {

        RateLimitProperties props = new RateLimitProperties();
        props.setEnabled(true);
        props.setRequestsPerWindow(scenario.requestsPerWindow());
        props.setWindowSeconds(scenario.windowSeconds());

        RateLimitFilter filter = new RateLimitFilter(props, new ClientIpResolver());
        FilterChain chain = (req, res) -> {
            // No-op downstream — the filter does not set a status on success, so the
            // default of 200 from MockHttpServletResponse stands in for "not 429".
        };

        for (int i = 0; i < scenario.totalRequests(); i++) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", API_PATH);
            request.setRemoteAddr(scenario.clientIp());
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, chain);

            int requestNumber = i + 1; // 1-indexed for readability
            if (requestNumber <= scenario.requestsPerWindow()) {
                // First requestsPerWindow requests: must NOT be rate-limited.
                assertThat(response.getStatus())
                        .as("request %d of %d (limit=%d) should not be 429",
                                requestNumber, scenario.totalRequests(), scenario.requestsPerWindow())
                        .isNotEqualTo(429);
            } else {
                // Subsequent requests within the same window: must be 429.
                assertThat(response.getStatus())
                        .as("request %d of %d (limit=%d) should be 429",
                                requestNumber, scenario.totalRequests(), scenario.requestsPerWindow())
                        .isEqualTo(429);

                // Retry-After header is present and parses as an integer in [1, windowSeconds].
                String retryAfter = response.getHeader("Retry-After");
                assertThat(retryAfter)
                        .as("Retry-After header on request %d", requestNumber)
                        .isNotNull()
                        .isNotBlank();

                long retrySeconds = Long.parseLong(retryAfter);
                assertThat(retrySeconds)
                        .as("Retry-After value on request %d", requestNumber)
                        .isBetween(1L, scenario.windowSeconds());

                // Body matches the standard error envelope (REQ 14.1).
                String body = response.getContentAsString();
                assertThat(body)
                        .as("error body on request %d", requestNumber)
                        .contains("\"error\"")
                        .contains("\"code\":\"RATE_LIMITED\"")
                        .contains("\"message\"");
            }
        }
    }

    /**
     * Scenario fixture: a fresh rate-limit configuration plus a sequence size {@code N}
     * exceeding the per-window budget by at least one request, so both branches of the
     * transition are exercised on every iteration.
     */
    record Scenario(String clientIp, int requestsPerWindow, long windowSeconds, int totalRequests) {}

    @Provide
    Arbitrary<Scenario> scenarios() {
        // Random client IP in the documentation range 203.0.113.0/24 (RFC 5737).
        Arbitrary<String> clientIp = Arbitraries.integers().between(1, 254)
                .map(octet -> "203.0.113." + octet);

        // Small per-window budget keeps each iteration fast (1..20 requests).
        Arbitrary<Integer> requestsPerWindow = Arbitraries.integers().between(1, 20);

        // Window length comfortably above the 1-second floor so Retry-After has room to
        // land anywhere in [1, windowSeconds]. Bucket4j refills greedily over this duration.
        Arbitrary<Long> windowSeconds = Arbitraries.longs().between(30L, 120L);

        // Number of extra requests issued beyond the budget so we always observe at
        // least one 429.
        Arbitrary<Integer> overflow = Arbitraries.integers().between(1, 10);

        return Combinators.combine(clientIp, requestsPerWindow, windowSeconds, overflow)
                .as((ip, limit, window, extra) -> new Scenario(ip, limit, window, limit + extra));
    }
}
