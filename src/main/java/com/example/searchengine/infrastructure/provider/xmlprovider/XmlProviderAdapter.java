package com.example.searchengine.infrastructure.provider.xmlprovider;

import com.example.searchengine.domain.provider.ContentProvider;
import com.example.searchengine.domain.provider.RawContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

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

    public XmlProviderAdapter(XmlProviderClient client, XmlContentMapper mapper) {
        this.client = client;
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return PROVIDER_NAME;
    }

    @Override
    public List<RawContent> fetch() {
        long startTime = System.currentTimeMillis();
        try {
            Optional<XmlFeedDto> feedOpt = client.fetchFeed();

            if (feedOpt.isEmpty()) {
                log.warn("provider={} fetch returned empty (malformed XML or transport failure)", PROVIDER_NAME);
                return List.of();
            }

            XmlFeedDto feed = feedOpt.get();

            if (feed.getItems() == null || feed.getItems().isEmpty()) {
                log.info("provider={} fetch returned 0 items", PROVIDER_NAME);
                return List.of();
            }

            List<RawContent> results = feed.getItems().stream()
                    .map(mapper::map)
                    .flatMap(Optional::stream)
                    .toList();

            long elapsedMs = System.currentTimeMillis() - startTime;
            log.info("provider={} fetch completed items={} elapsedMs={}",
                    PROVIDER_NAME, results.size(), elapsedMs);

            return results;
        } catch (Exception e) {
            long elapsedMs = System.currentTimeMillis() - startTime;
            log.error("provider={} fetch failed elapsedMs={} cause={}",
                    PROVIDER_NAME, elapsedMs, e.getMessage());
            return List.of();
        }
    }
}
