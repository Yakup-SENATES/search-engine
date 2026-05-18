package com.example.searchengine.infrastructure.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Typed configuration properties for the search export feature.
 * Binds to the {@code export.search} prefix in {@code application.yaml}.
 *
 * <p>Validates Requirement 5.3: the maximum number of rows a single export
 * call may return is configurable with a default of 1000.</p>
 *
 * <ul>
 *   <li>{@code export.search.max-rows} — defaults to {@code 1000};
 *       must be at least 1 (REQ 5.3).</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "export.search")
@Validated
public class ExportProperties {

    /** REQ 5.3: maximum rows a single export call may return; default 1000. */
    @Min(1)
    private int maxRows = 1000;

    public int getMaxRows() {
        return maxRows;
    }

    public void setMaxRows(int maxRows) {
        this.maxRows = maxRows;
    }
}
