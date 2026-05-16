package com.example.searchengine.infrastructure.provider.xmlprovider;

import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Unmarshaller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.StringReader;
import java.util.Optional;

import static com.example.searchengine.infrastructure.provider.HttpClientConfig.XML_PROVIDER;

/**
 * HTTP client for the XML content provider. Uses the configured {@code xmlRestClient}
 * bean with Resilience4j retry wrapping the HTTP call.
 *
 * <p>Parses the XML response body into an {@link XmlFeedDto} using JAXB.
 * Returns {@link Optional#empty()} on malformed XML (REQ 3.6).</p>
 */
@Component
public class XmlProviderClient {

    private static final Logger log = LoggerFactory.getLogger(XmlProviderClient.class);

    private final RestClient restClient;
    private final Retry retry;
    private final JAXBContext jaxbContext;

    public XmlProviderClient(@Qualifier("xmlRestClient") RestClient restClient,
                             RetryRegistry retryRegistry) {
        this.restClient = restClient;
        this.retry = retryRegistry.retry(XML_PROVIDER);
        try {
            this.jaxbContext = JAXBContext.newInstance(XmlFeedDto.class);
        } catch (JAXBException e) {
            throw new IllegalStateException("Failed to initialize JAXB context for XmlFeedDto", e);
        }
    }

    /**
     * Fetches the XML feed from the provider endpoint.
     *
     * @return the parsed feed DTO, or empty if the response is malformed or the call fails
     */
    public Optional<XmlFeedDto> fetchFeed() {
        try {
            String responseBody = Retry.decorateSupplier(retry, () ->
                    restClient.get()
                            .retrieve()
                            .body(String.class)
            ).get();

            if (responseBody == null || responseBody.isBlank()) {
                log.warn("XML provider returned empty response body");
                return Optional.empty();
            }

            return parseXml(responseBody);
        } catch (Exception e) {
            log.error("XML provider fetch failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<XmlFeedDto> parseXml(String xml) {
        try {
            Unmarshaller unmarshaller = jaxbContext.createUnmarshaller();
            XmlFeedDto feed = (XmlFeedDto) unmarshaller.unmarshal(new StringReader(xml));
            return Optional.ofNullable(feed);
        } catch (JAXBException e) {
            log.error("Malformed XML from provider: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
