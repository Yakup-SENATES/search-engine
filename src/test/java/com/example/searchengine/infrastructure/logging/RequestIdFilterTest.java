package com.example.searchengine.infrastructure.logging;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link RequestIdFilter}.
 *
 * <p>Validates Requirements 16.1 and 16.2: the filter must propagate a
 * per-request correlation id into SLF4J MDC, mirror it on the response, and
 * always clear MDC when the request ends.
 */
class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void usesIncomingHeaderWhenPresent() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader(RequestIdFilter.HEADER_NAME, "abc-123");

        AtomicReference<String> mdcDuringChain = new AtomicReference<>();
        FilterChain chain = (req, res) -> mdcDuringChain.set(MDC.get(RequestIdFilter.MDC_KEY));

        filter.doFilter(request, response, chain);

        assertThat(mdcDuringChain.get()).isEqualTo("abc-123");
        assertThat(response.getHeader(RequestIdFilter.HEADER_NAME)).isEqualTo("abc-123");
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void generatesUuidWhenHeaderMissing() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<String> mdcDuringChain = new AtomicReference<>();
        FilterChain chain = (req, res) -> mdcDuringChain.set(MDC.get(RequestIdFilter.MDC_KEY));

        filter.doFilter(request, response, chain);

        String generated = mdcDuringChain.get();
        assertThat(generated).isNotBlank();
        assertThat(UUID.fromString(generated)).isNotNull();
        assertThat(response.getHeader(RequestIdFilter.HEADER_NAME)).isEqualTo(generated);
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void generatesUuidWhenHeaderBlank() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader(RequestIdFilter.HEADER_NAME, "   ");

        AtomicReference<String> mdcDuringChain = new AtomicReference<>();
        FilterChain chain = (req, res) -> mdcDuringChain.set(MDC.get(RequestIdFilter.MDC_KEY));

        filter.doFilter(request, response, chain);

        String generated = mdcDuringChain.get();
        assertThat(generated).isNotBlank();
        assertThat(UUID.fromString(generated)).isNotNull();
        assertThat(response.getHeader(RequestIdFilter.HEADER_NAME)).isEqualTo(generated);
    }

    @Test
    void trimsLeadingAndTrailingWhitespaceFromHeader() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader(RequestIdFilter.HEADER_NAME, "  trace-007  ");

        AtomicReference<String> mdcDuringChain = new AtomicReference<>();
        FilterChain chain = (req, res) -> mdcDuringChain.set(MDC.get(RequestIdFilter.MDC_KEY));

        filter.doFilter(request, response, chain);

        assertThat(mdcDuringChain.get()).isEqualTo("trace-007");
        assertThat(response.getHeader(RequestIdFilter.HEADER_NAME)).isEqualTo("trace-007");
    }

    @Test
    void clearsMdcEvenWhenDownstreamThrows() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader(RequestIdFilter.HEADER_NAME, "boom-id");

        FilterChain explodingChain = (ServletRequest req, ServletResponse res) -> {
            assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isEqualTo("boom-id");
            throw new ServletException("downstream failure");
        };

        assertThatThrownBy(() -> filter.doFilter(request, response, explodingChain))
                .isInstanceOf(ServletException.class)
                .hasMessage("downstream failure");

        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
        assertThat(response.getHeader(RequestIdFilter.HEADER_NAME)).isEqualTo("boom-id");
    }

    @Test
    void onlyExecutesOncePerRequest() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        // First pass: real filter sets MDC, mirrors header, clears MDC.
        filter.doFilter(request, response, new MockFilterChain());
        String firstId = response.getHeader(RequestIdFilter.HEADER_NAME);
        assertThat(firstId).isNotBlank();

        // Mark request as already filtered (mirroring what OncePerRequestFilter
        // does internally on a forwarded dispatch within the same request).
        String alreadyFilteredAttribute =
                filter.getClass().getName() + ".FILTERED";
        request.setAttribute(alreadyFilteredAttribute, Boolean.TRUE);
        response.reset();

        AtomicReference<Boolean> innerInvoked = new AtomicReference<>(false);
        FilterChain chain = (req, res) -> innerInvoked.set(true);
        filter.doFilter(request, response, chain);

        // Inner chain should still be invoked (filter passes through), but the
        // filter must NOT mutate MDC or the response header a second time.
        assertThat(innerInvoked.get()).isTrue();
        assertThat(response.getHeader(RequestIdFilter.HEADER_NAME)).isNull();
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }
}
