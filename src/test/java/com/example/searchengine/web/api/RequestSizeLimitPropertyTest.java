package com.example.searchengine.web.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.searchengine.application.search.SearchQuery;
import com.example.searchengine.application.search.SearchResult;
import com.example.searchengine.application.search.SearchService;
import com.example.searchengine.web.error.RequestSizeLimitFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Property-based test for the request size limit enforced by
 * {@link RequestSizeLimitFilter}.
 *
 * <p><b>Validates: Requirements 19.5</b>
 *
 * <p>Property: {@code Feature: search-engine-service, Property 15: Request body / query size limit}
 *
 * <p>For any total request size {@code N = bodyBytes + queryStringBytes}:
 * <ul>
 *   <li>the response status is 413 if and only if {@code N >= 8193};</li>
 *   <li>when 413 is returned, the downstream chain — and therefore
 *       {@code SearchService} — is never invoked.</li>
 * </ul>
 *
 * <p>The test wires {@link RequestSizeLimitFilter} directly against
 * {@link MockHttpServletRequest} / {@link MockHttpServletResponse} and a mocked
 * {@link FilterChain} that fails the test if invoked when the request was
 * supposed to be rejected. This isolates the property from the rest of the web
 * stack so the assertion is purely about the filter's enforcement logic.
 */
class RequestSizeLimitPropertyTest {

    /** Threshold from REQ 19.5: total >= 8193 must be rejected with 413. */
    private static final int REJECT_THRESHOLD = 8193;

    /**
     * Range explored by the generator. Spans values well below, exactly at,
     * and well above the threshold to give jqwik room to shrink toward the
     * boundary {@code 8192 / 8193}.
     */
    private static final int MAX_BYTES_EACH = 16384;

    @Property(tries = 200)
    @Label("Feature: search-engine-service, Property 15: Request body / query size limit")
    void requestSizeLimit_enforces413AtOrAbove8193_andSkipsSearchService(
            @ForAll("requestSizes") RequestSizes sizes) throws ServletException, IOException {

        long total = (long) sizes.bodyBytes() + (long) sizes.queryStringBytes();

        // Arrange: filter under test, mocked downstream chain, and a mocked SearchService
        // that the chain would invoke if the request were forwarded. Wiring the chain
        // to call the SearchService lets us assert non-invocation directly per the
        // property statement ("Search_Service is never invoked when 413 is returned").
        RequestSizeLimitFilter filter = new RequestSizeLimitFilter();
        SearchService searchService = mock(SearchService.class);
        when(searchService.search(any(SearchQuery.class)))
                .thenReturn(new SearchResult(List.of(), 0L, 1, 10));

        MockHttpServletRequest request = buildRequest(sizes);
        MockHttpServletResponse response = new MockHttpServletResponse();
        InvokingFilterChain chain = new InvokingFilterChain(searchService);

        // Act
        filter.doFilter(request, response, chain);

        // Assert: status is 413 iff total >= 8193 (REQ 19.5).
        if (total >= REJECT_THRESHOLD) {
            assertThat(response.getStatus())
                    .as("total=%d (body=%d, query=%d) must be rejected with 413",
                            total, sizes.bodyBytes(), sizes.queryStringBytes())
                    .isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE.value());

            // Body matches the standard error envelope (REQ 14.1).
            assertThat(response.getContentAsString())
                    .contains("\"PAYLOAD_TOO_LARGE\"");

            // Chain was not invoked, therefore SearchService was not invoked.
            assertThat(chain.invocationCount())
                    .as("filter chain must not be forwarded for an oversized request")
                    .isZero();
            verify(searchService, never()).search(any(SearchQuery.class));
        } else {
            assertThat(response.getStatus())
                    .as("total=%d (body=%d, query=%d) must be forwarded (status != 413)",
                            total, sizes.bodyBytes(), sizes.queryStringBytes())
                    .isNotEqualTo(HttpStatus.PAYLOAD_TOO_LARGE.value());

            // Chain was invoked exactly once for an in-budget request.
            assertThat(chain.invocationCount())
                    .as("filter chain must be forwarded exactly once for an in-budget request")
                    .isEqualTo(1);
            verify(searchService, times(1)).search(any(SearchQuery.class));
        }
    }

    // ─── Generators ───────────────────────────────────────────────────────────

    @Provide
    Arbitrary<RequestSizes> requestSizes() {
        Arbitrary<Integer> bodyBytes = Arbitraries.integers().between(0, MAX_BYTES_EACH);
        Arbitrary<Integer> queryBytes = Arbitraries.integers().between(0, MAX_BYTES_EACH);
        return Combinators.combine(bodyBytes, queryBytes).as(RequestSizes::new);
    }

    /**
     * Generated a pair of declared body length and query string length, both in bytes.
     */
    record RequestSizes(int bodyBytes, int queryStringBytes) {
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Builds a {@link MockHttpServletRequest} whose {@code getContentLength()}
     * equals {@code sizes.bodyBytes()} and whose {@code getQueryString()} is a
     * UTF-8 string of length {@code sizes.queryStringBytes()}.
     *
     * <p>Using ASCII characters ('a') for the query keeps the byte length equal
     * to the character length under UTF-8, which is exactly what the filter
     * measures.
     */
    private static MockHttpServletRequest buildRequest(RequestSizes sizes) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/search");
        // MockHttpServletRequest.getContentLength() returns the length of the
        // byte array supplied to setContent(...), not the "Content-Length"
        // header. Allocate a byte array of the requested length so the filter
        // sees the same value the production servlet container would.
        request.setContent(new byte[sizes.bodyBytes()]);
        request.setContentType("application/octet-stream");

        if (sizes.queryStringBytes() > 0) {
            request.setQueryString(repeatAscii(sizes.queryStringBytes()));
        } else {
            request.setQueryString(null);
        }
        return request;
    }

    private static String repeatAscii(int length) {
        char[] chars = new char[length];
        Arrays.fill(chars, 'a');
        return new String(chars);
    }

    /**
     * Filter chain that records invocation and, when invoked, calls the supplied
     * {@link SearchService} mock so the property can verify whether it was
     * reached. The chain itself remains a passive observer of the filter's
     * decision.
     */
    private static final class InvokingFilterChain implements FilterChain {

        private final SearchService searchService;
        private int invocations;

        InvokingFilterChain(SearchService searchService) {
            this.searchService = searchService;
        }

        @Override
        public void doFilter(jakarta.servlet.ServletRequest request,
                             jakarta.servlet.ServletResponse response) {
            invocations++;
            // Simulate the controller calling SearchService; this lets us prove
            // SearchService is NOT reached when the filter rejects upstream.
            searchService.search(new SearchQuery("q", null, "score", 1, 10));
        }

        int invocationCount() {
            return invocations;
        }
    }

}
