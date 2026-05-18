package com.example.searchengine.infrastructure.admin;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Servlet filter that protects the admin surface ({@code /api/v1/admin/}) with a
 * static API token validated via constant-time comparison.
 *
 * <p>Behaviour:
 * <ul>
 *   <li>If {@code admin.auth.token} is empty or blank, the admin surface is considered
 *       disabled and all requests pass through without authentication (design.md § 7).</li>
 *   <li>For requests whose URI starts with {@code /api/v1/admin/}, the filter reads the
 *       {@code X-Admin-Token} request header and compares it against the configured token
 *       using {@link MessageDigest#isEqual(byte[], byte[])} (constant-time) to prevent
 *       timing attacks.</li>
 *   <li>On mismatch or missing header, the filter responds HTTP 401 with the standardized
 *       error envelope {@code {"error":{"code":"UNAUTHORIZED","message":"Invalid or missing admin token"}}}.</li>
 *   <li>Non-admin paths are always passed through regardless of the header.</li>
 * </ul>
 *
 * <p>Ordered at {@code HIGHEST_PRECEDENCE + 50} so it runs after the {@code RequestIdFilter}
 * (HIGHEST_PRECEDENCE) but before the {@code RateLimitFilter} (HIGHEST_PRECEDENCE + 100).
 *
 * @see AdminAuthProperties
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 50)
public class AdminAuthFilter extends OncePerRequestFilter {

    /** URI prefix that triggers admin authentication. */
    static final String ADMIN_PATH_PREFIX = "/api/v1/admin/";

    /** Header name carrying the admin token. */
    static final String TOKEN_HEADER = "X-Admin-Token";

    /** JSON body returned on authentication failure. */
    static final String UNAUTHORIZED_BODY =
            "{\"error\":{\"code\":\"UNAUTHORIZED\",\"message\":\"Invalid or missing admin token\"}}";

    private final AdminAuthProperties properties;

    public AdminAuthFilter(AdminAuthProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        // If admin auth is disabled (token not configured), pass through everything
        if (!isAdminAuthEnabled()) {
            filterChain.doFilter(request, response);
            return;
        }

        // Only enforce on admin paths
        if (!isAdminRequest(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        // Validate the token
        String providedToken = request.getHeader(TOKEN_HEADER);
        if (providedToken == null || !constantTimeEquals(properties.getToken(), providedToken)) {
            sendUnauthorized(response);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean isAdminAuthEnabled() {
        String token = properties.getToken();
        return token != null && !token.isBlank();
    }

    private boolean isAdminRequest(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri != null && uri.startsWith(ADMIN_PATH_PREFIX);
    }

    /**
     * Constant-time comparison using {@link MessageDigest#isEqual(byte[], byte[])} to
     * prevent timing-based side-channel attacks on the token value.
     */
    private boolean constantTimeEquals(String expected, String actual) {
        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        byte[] actualBytes = actual.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedBytes, actualBytes);
    }

    private void sendUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(UNAUTHORIZED_BODY);
    }
}
