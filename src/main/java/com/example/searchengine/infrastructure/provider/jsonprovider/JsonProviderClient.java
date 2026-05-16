package com.example.searchengine.infrastructure.provider.jsonprovider;

import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import static com.example.searchengine.infrastructure.provider.HttpClientConfig.JSON_PROVIDER;

/**
 * HTTP client for the JSON content provider.
 *
 * <p>Uses the pre-configured {@code jsonRestClient} bean (with 5 s connect / 10 s read timeouts)
 * and wraps the HTTP call with Resilience4j retry (max 3 attempts, exponential backoff 500 ms × 2).</p>
 *
 * <p>REQ 2.1, REQ 21.1, REQ 21.2</p>
 */
@Component
public class JsonProviderClient {

    private final RestClient restClient;
    private final Retry retry;

    public JsonProviderClient(
            @Qualifier("jsonRestClient") RestClient restClient,
            RetryRegistry retryRegistry
    ) {
        this.restClient = restClient;
        this.retry = retryRegistry.retry(JSON_PROVIDER);
    }

    /**
     * Fetches the JSON provider response, retrying on transient failures.
     *
     * @return the parsed provider response
     * @throws org.springframework.web.client.RestClientException on non-2xx responses
     * @throws org.springframework.web.client.ResourceAccessException on network failures
     */
    public JsonProviderResponse fetch() {
        return Retry.decorateSupplier(retry, () ->
                restClient.get()
                        .retrieve()
                        .body(JsonProviderResponse.class)
        ).get();
    }
}
