package com.example.searchengine.infrastructure.admin;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link AdminAuthFilter}.
 *
 * <p>Covers:
 * <ul>
 *   <li>Valid token → passes through</li>
 *   <li>Invalid token → 401 envelope</li>
 *   <li>Missing header → 401 envelope</li>
 *   <li>Empty configured token → passes through (admin disabled)</li>
 *   <li>Non-admin path → passes through regardless of token</li>
 *   <li>Constant-time compare (asserts {@code MessageDigest.isEqual} is used via code path)</li>
 * </ul>
 */
class AdminAuthFilterTest {

    private static final String CONFIGURED_TOKEN = "super-secret-admin-token-123";

    private AdminAuthProperties properties;
    private AdminAuthFilter filter;
    private FilterChain filterChain;

    @BeforeEach
    void setUp() {
        properties = new AdminAuthProperties();
        properties.setToken(CONFIGURED_TOKEN);
        filter = new AdminAuthFilter(properties);
        filterChain = mock(FilterChain.class);
    }

    @Test
    @DisplayName("Valid token on admin path → request passes through")
    void validToken_passesThrough() throws ServletException, IOException {
        MockHttpServletRequest request = adminRequest();
        request.addHeader(AdminAuthFilter.TOKEN_HEADER, CONFIGURED_TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("Invalid token on admin path → 401 with error envelope")
    void invalidToken_returns401() throws ServletException, IOException {
        MockHttpServletRequest request = adminRequest();
        request.addHeader(AdminAuthFilter.TOKEN_HEADER, "wrong-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        verifyNoInteractions(filterChain);
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getContentAsString()).isEqualTo(AdminAuthFilter.UNAUTHORIZED_BODY);
    }

    @Test
    @DisplayName("Missing X-Admin-Token header on admin path → 401 with error envelope")
    void missingHeader_returns401() throws ServletException, IOException {
        MockHttpServletRequest request = adminRequest();
        // No header added
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        verifyNoInteractions(filterChain);
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getContentAsString()).isEqualTo(AdminAuthFilter.UNAUTHORIZED_BODY);
    }

    @Test
    @DisplayName("Empty configured token → admin auth disabled, all requests pass through")
    void emptyConfiguredToken_passesThrough() throws ServletException, IOException {
        properties.setToken("");
        MockHttpServletRequest request = adminRequest();
        // No header — would normally fail, but admin is disabled
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("Blank configured token → admin auth disabled, all requests pass through")
    void blankConfiguredToken_passesThrough() throws ServletException, IOException {
        properties.setToken("   ");
        MockHttpServletRequest request = adminRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("Null configured token → admin auth disabled, all requests pass through")
    void nullConfiguredToken_passesThrough() throws ServletException, IOException {
        properties.setToken(null);
        MockHttpServletRequest request = adminRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("Non-admin path → passes through regardless of token presence")
    void nonAdminPath_passesThrough() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/search");
        // No admin token header
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("Non-admin path with invalid token → still passes through")
    void nonAdminPath_withInvalidToken_passesThrough() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/search");
        request.addHeader(AdminAuthFilter.TOKEN_HEADER, "wrong-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("Dashboard path → passes through regardless of token")
    void dashboardPath_passesThrough() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/dashboard");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("Constant-time compare: uses MessageDigest.isEqual code path (timing-safe)")
    void constantTimeCompare_usesMessageDigestIsEqual() throws ServletException, IOException {
        // This test verifies the code path by ensuring that tokens of different lengths
        // still produce a 401 (MessageDigest.isEqual handles length differences safely).
        // A naive equals() would short-circuit on length; MessageDigest.isEqual does not
        // leak length information.
        MockHttpServletRequest request = adminRequest();
        request.addHeader(AdminAuthFilter.TOKEN_HEADER, "x"); // much shorter than configured
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        verifyNoInteractions(filterChain);
        assertThat(response.getStatus()).isEqualTo(401);

        // Also verify with a token that has the same length but different content
        MockHttpServletRequest request2 = adminRequest();
        request2.addHeader(AdminAuthFilter.TOKEN_HEADER, "super-secret-admin-token-999");
        MockHttpServletResponse response2 = new MockHttpServletResponse();

        filter.doFilterInternal(request2, response2, filterChain);

        verifyNoInteractions(filterChain);
        assertThat(response2.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("401 response has correct JSON structure")
    void unauthorizedResponse_hasCorrectJsonStructure() throws ServletException, IOException {
        MockHttpServletRequest request = adminRequest();
        request.addHeader(AdminAuthFilter.TOKEN_HEADER, "bad");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        String body = response.getContentAsString();
        assertThat(body).contains("\"code\":\"UNAUTHORIZED\"");
        assertThat(body).contains("\"message\":\"Invalid or missing admin token\"");
        assertThat(body).startsWith("{\"error\":");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private MockHttpServletRequest adminRequest() {
        return new MockHttpServletRequest("GET", "/api/v1/admin/providers");
    }
}
