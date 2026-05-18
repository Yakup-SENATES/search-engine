package com.example.searchengine.infrastructure.provider.xmlprovider;

import com.example.searchengine.domain.provider.ContentProvider;
import com.example.searchengine.domain.provider.RawContent;
import com.example.searchengine.infrastructure.metrics.ProviderFetchMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Strategy Pattern implementation of {@link ContentProvider} for the XML-based
 * content provider (Provider 2).
 *
 * <p>Behaviors:
 * <ul>
 *   <li>Returns {@code []} on malformed XML (REQ 3.6)</li>
 *   <li>Defaults absent optional elements to zero/empty (REQ 3.5)</li>
 *   <li>Skips individual items that fail to parse (REQ 3.5)</li>
 *   <li>Logs provider name and elapsed ms per fetch (REQ 16.4)</li>
 *   <li>Records {@code provider_fetch_duration_seconds} and
 *       {@code provider_fetch_failures_total} on every outcome via
 *       {@link ProviderFetchMetrics} (REQ 1.2, 1.3 — operability quick-wins)</li>
 * </ul>
 *
 * <p>REQ 1.2, 1.4, 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 5.6, 16.4</p>
 */
@Component
public class XmlProviderAdapter implements ContentProvider {

    private static final Logger log = LoggerFactory.getLogger(XmlProviderAdapter.class);
    private static final String PROVIDER_NAME = "provider2-xml";

    private final XmlProviderClient client;
    private final XmlContentMapper mapper;
    private final ProviderFetchMetrics metrics;

    public XmlProviderAdapter(XmlProviderClient client,
                              XmlContentMapper mapper,
                              ProviderFetchMetrics metrics) {
        this.client = client;
        this.mapper = mapper;
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
            Optional<XmlFeedDto> feedOpt = client.fetchFeed();

            if (feedOpt.isEmpty()) {
                log.warn("provider={} fetch returned empty (malformed XML or transport failure)", PROVIDER_NAME);
                // The client already swallowed the underlying exception and reported it via
                // logs, but at the adapter boundary the outcome is still a failed fetch — no
                // payload arrived. Tag it as a failure so operators can alert on it.
                return List.of();
            }

            XmlFeedDto feed = feedOpt.get();

            if (feed.getItems() == null || feed.getItems().isEmpty()) {
                log.info("provider={} fetch returned 0 items", PROVIDER_NAME);
                success = true;
                return List.of();
            }

            List<RawContent> results = feed.getItems().stream()
                    .map(mapper::map)
                    .flatMap(Optional::stream)
                    .toList();

            long elapsedMs = elapsedMillis(startNanos);
            log.info("provider={} fetch completed items={} elapsedMs={}",
                    PROVIDER_NAME, results.size(), elapsedMs);
            success = true;
            return results;
        } catch (Exception e) {
            long elapsedMs = elapsedMillis(startNanos);
            log.error("provider={} fetch failed elapsedMs={} cause={}",
                    PROVIDER_NAME, elapsedMs, e.getMessage());
            return List.of();
        } finally {
            metrics.record(PROVIDER_NAME, Duration.ofNanos(System.nanoTime() - startNanos), success);
        }
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
