package com.example.searchengine.infrastructure.provider.jsonprovider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import static com.example.searchengine.infrastructure.provider.HttpClientConfig.JSON_PROVIDER;

/**
 * HTTP client for the JSON content provider.
 *
 * <p>Uses the pre-configured {@code jsonRestClient} bean (with 5 s connect / 10 s read timeouts)
 * and wraps the HTTP call with Resilience4j retry (max 3 attempts, exponential backoff 500 ms × 2).</p>
 *
 * <p>The body is fetched as a raw {@code String} and parsed with Jackson explicitly so the
 * client tolerates upstream services (such as GitHub raw) that return JSON content with
 * {@code Content-Type: text/plain;charset=utf-8} instead of {@code application/json}.
 * Spring's default {@code MappingJackson2HttpMessageConverter} only matches
 * {@code application/json} and {@code application/*+json}, which would otherwise
 * cause a "no suitable HttpMessageConverter found" error against {@code text/plain}
 * responses (REQ 2.2 — accept the documented JSON schema regardless of media type).</p>
 *
 * <p>REQ 2.1, REQ 21.1, REQ 21.2</p>
 */
@Component
public class JsonProviderClient {

    private static final Logger log = LoggerFactory.getLogger(JsonProviderClient.class);

    private final RestClient restClient;
    private final Retry retry;
    private final ObjectMapper objectMapper;

    public JsonProviderClient(
            @Qualifier("jsonRestClient") RestClient restClient,
            RetryRegistry retryRegistry,
            ObjectMapper objectMapper
    ) {
        this.restClient = restClient;
        this.retry = retryRegistry.retry(JSON_PROVIDER);
        this.objectMapper = objectMapper;
    }

    /**
     * Fetches the JSON provider response, retrying on transient failures.
     *
     * @return the parsed provider response
     * @throws org.springframework.web.client.RestClientException on non-2xx responses
     * @throws org.springframework.web.client.ResourceAccessException on network failures
     * @throws IllegalStateException if the response body cannot be parsed as JSON
     */
    public JsonProviderResponse fetch() {
        String body = Retry.decorateSupplier(retry, () ->
                restClient.get()
                        .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE
                                + ", " + MediaType.TEXT_PLAIN_VALUE)
                        .retrieve()
                        .body(String.class)
        ).get();

        if (body == null || body.isBlank()) {
            log.warn("JSON provider returned empty response body");
            throw new IllegalStateException("JSON provider returned an empty response body");
        }

        try {
            return objectMapper.readValue(body, JsonProviderResponse.class);
        } catch (JsonProcessingException e) {
            log.error("Failed to parse JSON provider response: {}", e.getOriginalMessage());
            throw new IllegalStateException("Failed to parse JSON provider response", e);
        }
    }
}
