package com.example.searchengine.infrastructure.provider.jsonprovider;

import com.example.searchengine.domain.provider.ContentProvider;
import com.example.searchengine.domain.provider.RawContent;
import com.example.searchengine.infrastructure.config.ProviderProperties;
import com.example.searchengine.infrastructure.metrics.ProviderFetchMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Strategy Pattern implementation of {@link ContentProvider} for the JSON-based provider.
 *
 * <p>Behavior:
 * <ul>
 *   <li>HTTP errors (non-2xx) → log and return empty list (REQ 2.4)</li>
 *   <li>Network failures (connection error, timeout) → log and return empty list (REQ 2.6)</li>
 *   <li>Per-item parse failures → log and skip the item, continue with others (REQ 2.5)</li>
 *   <li>Logs provider name and elapsed ms per fetch (REQ 16.4)</li>
 *   <li>Records {@code provider_fetch_duration_seconds} and
 *       {@code provider_fetch_failures_total} on every outcome via
 *       {@link ProviderFetchMetrics} (REQ 1.2, 1.3 — operability quick-wins)</li>
 * </ul>
 */
@Component
public class JsonProviderAdapter implements ContentProvider {

    private static final Logger log = LoggerFactory.getLogger(JsonProviderAdapter.class);
    private static final String PROVIDER_NAME = "provider1-json";

    private final JsonProviderClient client;
    private final JsonContentMapper mapper;
    private final String baseUrl;
    private final ProviderFetchMetrics metrics;

    public JsonProviderAdapter(
            JsonProviderClient client,
            ProviderProperties providerProperties,
            ProviderFetchMetrics metrics
    ) {
        this.client = client;
        this.mapper = new JsonContentMapper();
        this.baseUrl = providerProperties.getJson().getBaseUrl();
        this.metrics = metrics;
    }

    @Override
    public String name() {
        return PROVIDER_NAME;
    }

    @Override
    public List<RawContent> fetch() {
        long startNanos = System.nanoTime();
        boolean success = false;
        try {
            JsonProviderResponse response = client.fetch();

            if (response == null || response.contents() == null) {
                log.warn("provider={} url={} returned null or empty response", name(), baseUrl);
                logElapsed(startNanos);
                success = true; // empty payload is a successful but uninteresting fetch
                return List.of();
            }

            List<RawContent> results = response.contents().stream()
                    .limit(1000)  // REQ 2.2: parse up to 1000 content items
                    .map(mapper::map)
                    .flatMap(Optional::stream)
                    .toList();

            log.info("provider={} fetched={} items elapsed={}ms", name(), results.size(),
                    elapsedMillis(startNanos));
            success = true;
            return results;

        } catch (RestClientException e) {
            log.error("provider={} url={} fetch failed cause={}", name(), baseUrl, e.getMessage());
            logElapsed(startNanos);
            return List.of();
        } catch (Exception e) {
            // Catch-all for unexpected failures (e.g. deserialization issues)
            log.error("provider={} url={} unexpected failure cause={}", name(), baseUrl, e.getMessage());
            logElapsed(startNanos);
            return List.of();
        } finally {
            metrics.record(PROVIDER_NAME, Duration.ofNanos(System.nanoTime() - startNanos), success);
        }
    }

    private void logElapsed(long startNanos) {
        log.info("provider={} elapsed={}ms", name(), elapsedMillis(startNanos));
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
