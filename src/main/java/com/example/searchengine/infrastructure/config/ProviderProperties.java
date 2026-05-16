package com.example.searchengine.infrastructure.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Typed configuration properties for external content providers.
 * Binds to the {@code providers} prefix in application.yaml.
 *
 * <p>Each provider entry declares its base URL and HTTP timeout settings.
 * Spring fails the application context if any required property is null/blank (REQ 18.3).
 */
@ConfigurationProperties(prefix = "providers")
@Validated
public class ProviderProperties {

    @Valid
    @NotNull
    private ProviderEntry json;

    @Valid
    @NotNull
    private ProviderEntry xml;

    public ProviderEntry getJson() {
        return json;
    }

    public void setJson(ProviderEntry json) {
        this.json = json;
    }

    public ProviderEntry getXml() {
        return xml;
    }

    public void setXml(ProviderEntry xml) {
        this.xml = xml;
    }

    /**
     * Configuration for a single provider endpoint.
     */
    public static class ProviderEntry {

        @NotBlank
        private String baseUrl;

        @Min(1)
        private int connectTimeoutMs = 5000;

        @Min(1)
        private int readTimeoutMs = 10000;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public int getConnectTimeoutMs() {
            return connectTimeoutMs;
        }

        public void setConnectTimeoutMs(int connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
        }

        public int getReadTimeoutMs() {
            return readTimeoutMs;
        }

        public void setReadTimeoutMs(int readTimeoutMs) {
            this.readTimeoutMs = readTimeoutMs;
        }
    }
}
