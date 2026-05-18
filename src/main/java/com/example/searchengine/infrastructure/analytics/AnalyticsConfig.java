package com.example.searchengine.infrastructure.analytics;

import com.example.searchengine.application.analytics.SearchAnalyticsSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Wires the appropriate {@link SearchAnalyticsSink} bean based on the
 * {@code analytics.search.*} properties.
 *
 * <p>Selection logic:</p>
 * <ul>
 *   <li>{@code analytics.search.enabled=false} → {@link NoOpSearchAnalyticsSink}</li>
 *   <li>{@code analytics.search.sink=none} → {@link NoOpSearchAnalyticsSink}</li>
 *   <li>{@code analytics.search.sink=log} → {@link LogSearchAnalyticsSink}</li>
 *   <li>{@code analytics.search.sink=db} (default) → {@link JdbcSearchAnalyticsSink}</li>
 * </ul>
 *
 * <p>REQ 4.5, 4.6</p>
 */
@Configuration
@EnableConfigurationProperties(AnalyticsProperties.class)
public class AnalyticsConfig {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsConfig.class);

    @Bean
    public SearchAnalyticsSink searchAnalyticsSink(AnalyticsProperties properties,
                                                   JdbcTemplate jdbcTemplate) {
        if (!properties.isEnabled()) {
            log.info("Search analytics disabled (analytics.search.enabled=false)");
            return new NoOpSearchAnalyticsSink();
        }

        String sink = properties.getSink();
        return switch (sink != null ? sink.toLowerCase() : "db") {
            case "log" -> {
                log.info("Search analytics sink: log");
                yield new LogSearchAnalyticsSink();
            }
            case "none" -> {
                log.info("Search analytics sink: none (no-op)");
                yield new NoOpSearchAnalyticsSink();
            }
            default -> {
                log.info("Search analytics sink: db (JDBC)");
                yield new JdbcSearchAnalyticsSink(jdbcTemplate);
            }
        };
    }
}
