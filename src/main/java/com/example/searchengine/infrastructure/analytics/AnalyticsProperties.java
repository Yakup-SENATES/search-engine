package com.example.searchengine.infrastructure.analytics;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externalized configuration for search analytics.
 *
 * <p>Bound to the {@code analytics.search} prefix in {@code application.yaml}.</p>
 *
 * <ul>
 *   <li>{@code analytics.search.enabled} — master kill-switch (default {@code true}).
 *       When {@code false}, a {@link NoOpSearchAnalyticsSink} is wired regardless
 *       of the {@code sink} value (REQ 4.6).</li>
 *   <li>{@code analytics.search.sink} — selects the persistence backend:
 *       {@code db} (default), {@code log}, or {@code none} (REQ 4.5).</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "analytics.search")
public class AnalyticsProperties {

    private boolean enabled = true;
    private String sink = "db";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getSink() {
        return sink;
    }

    public void setSink(String sink) {
        this.sink = sink;
    }
}
