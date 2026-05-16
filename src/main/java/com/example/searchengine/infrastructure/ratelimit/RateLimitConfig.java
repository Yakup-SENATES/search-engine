package com.example.searchengine.infrastructure.ratelimit;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Enables {@link RateLimitProperties} binding for the rate-limit infrastructure package.
 *
 * <p>Filter registration is handled by Spring Boot's auto-detection of {@code Filter}
 * beans: both {@link RateLimitFilter} and {@link LoggingOnlyRateLimitFilter} are
 * {@code @Component}s annotated with {@code @Order} so the embedded servlet container
 * registers them with the correct precedence. Each filter checks the request URI and
 * only acts on {@code /api/v1/**} (REQ 13.1).
 */
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfig {
}
