package com.example.searchengine.infrastructure.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the configuration-properties classes that live in
 * {@code infrastructure.config} but are not already enabled alongside
 * a feature-specific configuration class
 * (e.g. {@link com.example.searchengine.infrastructure.provider.HttpClientConfig}
 * already enables {@link ProviderProperties}).
 *
 * <p>Currently exposes {@link AggregatorProperties} so that
 * {@code aggregator.sync.*} values are bound, validated, and available for
 * injection into scheduling components.</p>
 */
@Configuration
@EnableConfigurationProperties(AggregatorProperties.class)
public class ConfigPropertiesConfig {
}
