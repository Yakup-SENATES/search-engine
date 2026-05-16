package com.example.searchengine.infrastructure.provider;

import com.example.searchengine.infrastructure.config.ProviderProperties;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Configures two named {@link RestClient} beans (one per provider) with HTTP timeouts
 * from {@link ProviderProperties}, and registers Resilience4j {@code TimeLimiter} and
 * {@code Retry} instances per provider name.
 *
 * <ul>
 *   <li>Connect timeout: 5 s (default, configurable per provider) — REQ 2.1</li>
 *   <li>Read timeout: 10 s (default, configurable per provider) — REQ 2.1</li>
 *   <li>TimeLimiter: 10 s overall call timeout — REQ 21.1</li>
 *   <li>Retry: max 3 attempts, exponential backoff 500 ms × 2 — REQ 21.2</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(ProviderProperties.class)
public class HttpClientConfig {

    public static final String JSON_PROVIDER = "jsonProvider";
    public static final String XML_PROVIDER = "xmlProvider";

    private final ProviderProperties providerProperties;

    public HttpClientConfig(ProviderProperties providerProperties) {
        this.providerProperties = providerProperties;
    }

    // ======================== RestClient Beans ========================

    @Bean("jsonRestClient")
    public RestClient jsonRestClient(RestClient.Builder builder) {
        ProviderProperties.ProviderEntry json = providerProperties.getJson();
        ClientHttpRequestFactory factory = createRequestFactory(json);
        return builder
                .baseUrl(json.getBaseUrl())
                .requestFactory(factory)
                .build();
    }

    @Bean("xmlRestClient")
    public RestClient xmlRestClient(RestClient.Builder builder) {
        ProviderProperties.ProviderEntry xml = providerProperties.getXml();
        ClientHttpRequestFactory factory = createRequestFactory(xml);
        return builder
                .baseUrl(xml.getBaseUrl())
                .requestFactory(factory)
                .build();
    }

    // ======================== Resilience4j TimeLimiter ========================

    @Bean
    public TimeLimiterRegistry timeLimiterRegistry() {
        TimeLimiterConfig jsonConfig = TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofSeconds(10))
                .cancelRunningFuture(true)
                .build();

        TimeLimiterConfig xmlConfig = TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofSeconds(10))
                .cancelRunningFuture(true)
                .build();

        TimeLimiterRegistry registry = TimeLimiterRegistry.ofDefaults();
        registry.timeLimiter(JSON_PROVIDER, jsonConfig);
        registry.timeLimiter(XML_PROVIDER, xmlConfig);
        return registry;
    }

    // ======================== Resilience4j Retry ========================

    @Bean
    public RetryRegistry retryRegistry() {
        RetryConfig jsonRetryConfig = RetryConfig.custom()
                .maxAttempts(3)
                .intervalFunction(IntervalFunction.ofExponentialBackoff(500, 2.0))
                .build();

        RetryConfig xmlRetryConfig = RetryConfig.custom()
                .maxAttempts(3)
                .intervalFunction(IntervalFunction.ofExponentialBackoff(500, 2.0))
                .build();

        RetryRegistry registry = RetryRegistry.ofDefaults();
        registry.retry(JSON_PROVIDER, jsonRetryConfig);
        registry.retry(XML_PROVIDER, xmlRetryConfig);
        return registry;
    }

    // ======================== Private Helpers ========================

    private ClientHttpRequestFactory createRequestFactory(ProviderProperties.ProviderEntry entry) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(Duration.ofMillis(entry.getConnectTimeoutMs()))
                .withReadTimeout(Duration.ofMillis(entry.getReadTimeoutMs()));
        return ClientHttpRequestFactoryBuilder.detect().build(settings);
    }
}
