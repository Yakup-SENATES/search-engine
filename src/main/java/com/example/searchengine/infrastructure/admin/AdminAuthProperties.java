package com.example.searchengine.infrastructure.admin;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Binds the {@code admin.auth.token} configuration property (env var
 * {@code ADMIN_API_TOKEN}) used by {@link AdminAuthFilter} to authenticate
 * operator requests to the admin surface.
 *
 * <p>When the token is empty or blank, the admin surface is considered disabled
 * and all requests pass through without authentication.
 */
@Component
@ConfigurationProperties(prefix = "admin.auth")
public class AdminAuthProperties {

    /**
     * The static API token that must be supplied in the {@code X-Admin-Token}
     * header for admin endpoints. Empty/blank = admin auth disabled.
     */
    private String token = "";

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }
}
