package com.example.searchengine.infrastructure.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Unit tests for {@link ClientIpResolver}.
 * <p>Validates Requirement 13.1: per-client tracking must work behind reverse proxies.
 */
class ClientIpResolverTest {

    private final ClientIpResolver resolver = new ClientIpResolver();

    @Test
    void usesXForwardedForFirstEntryWhenPresent() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Forwarded-For", "203.0.113.7, 198.51.100.1, 10.0.0.1");

        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.7");
    }

    @Test
    void trimsWhitespaceFromXForwardedForFirstEntry() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Forwarded-For", "  203.0.113.42  ,198.51.100.1");

        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.42");
    }

    @Test
    void fallsBackToRemoteAddrWhenHeaderMissing() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.168.1.10");

        assertThat(resolver.resolve(request)).isEqualTo("192.168.1.10");
    }

    @Test
    void fallsBackToRemoteAddrWhenHeaderBlank() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.168.1.20");
        request.addHeader("X-Forwarded-For", "   ");

        assertThat(resolver.resolve(request)).isEqualTo("192.168.1.20");
    }

    @Test
    void fallsBackToRemoteAddrWhenHeaderFirstEntryEmpty() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.168.1.30");
        request.addHeader("X-Forwarded-For", " , 198.51.100.1");

        assertThat(resolver.resolve(request)).isEqualTo("192.168.1.30");
    }

    @Test
    void returnsUnknownWhenAllSourcesAreBlank() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(null);

        assertThat(resolver.resolve(request)).isEqualTo("unknown");
    }
}
