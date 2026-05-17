package com.example.searchengine.infrastructure.metrics;

import org.springframework.context.annotation.Configuration;

/**
 * Home for cross-cutting metric beans introduced by the operability quick-wins
 * feature (see {@code .kiro/specs/operability-quick-wins/design.md}).
 *
 * <p>This class is intentionally a marker {@code @Configuration} — it does
 * <strong>not</strong> redeclare a {@link io.micrometer.core.instrument.MeterRegistry}.
 * Spring Boot's actuator + the {@code micrometer-registry-prometheus} runtime
 * dependency already auto-configure a Prometheus-backed registry. Exposure of
 * the {@code /actuator/prometheus} endpoint is driven entirely from
 * {@code application.yaml} via
 * {@code management.endpoints.web.exposure.include}.</p>
 *
 * <p>Subsequent tasks (2.2 — 2.5) will add small {@code @Component}
 * collaborators (e.g. {@code ProviderFetchMetrics}, {@code SearchMetrics},
 * {@code IngestMetrics}, {@code RateLimitMetrics}) in this same package. Those
 * components will be picked up by Spring's component scan; this configuration
 * class simply anchors the package as a recognised configuration root and
 * documents the intent for future readers.</p>
 *
 * <p>Validates: Requirements 1.1, 1.2 (operability quick-wins).</p>
 */
@Configuration
public class MetricsConfig {
    // Marker configuration — meter beans are registered as @Component
    // collaborators in this package by later tasks.
}
