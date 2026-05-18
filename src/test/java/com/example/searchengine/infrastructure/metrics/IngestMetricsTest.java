package com.example.searchengine.infrastructure.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link IngestMetrics} backed by a {@link SimpleMeterRegistry}.
 *
 * <p>Validates Requirement 1.6 (operability quick-wins): the meter names, the
 * {@code provider} / {@code reason} tag schemas, and counter increments on the
 * insert / update / reject paths.</p>
 */
class IngestMetricsTest {

    private MeterRegistry registry;
    private IngestMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new IngestMetrics(registry);
    }

    @Test
    @DisplayName("Constructor pre-registers insert + update counters for the shipped providers (REQ 1.6)")
    void preRegistersInsertAndUpdateCountersForShippedProviders() {
        for (String provider : new String[]{"provider1-json", "provider2-xml"}) {
            assertThat(registry.find(IngestMetrics.INSERTED_METER).tag("provider", provider).counter())
                    .as("inserted counter for %s", provider)
                    .isNotNull();
            assertThat(registry.find(IngestMetrics.UPDATED_METER).tag("provider", provider).counter())
                    .as("updated counter for %s", provider)
                    .isNotNull();
        }
    }

    @Test
    @DisplayName("recordInserted increments only the insert counter for that provider (REQ 1.6)")
    void recordInsertedIncrementsOnlyInsertCounter() {
        metrics.recordInserted("provider1-json");
        metrics.recordInserted("provider1-json");

        Counter inserted = registry.find(IngestMetrics.INSERTED_METER)
                .tag("provider", "provider1-json").counter();
        Counter updated = registry.find(IngestMetrics.UPDATED_METER)
                .tag("provider", "provider1-json").counter();

        assertThat(inserted.count()).isEqualTo(2.0);
        assertThat(updated.count()).isZero();
    }

    @Test
    @DisplayName("recordUpdated increments only the update counter for that provider (REQ 1.6)")
    void recordUpdatedIncrementsOnlyUpdateCounter() {
        metrics.recordUpdated("provider2-xml");

        Counter updated = registry.find(IngestMetrics.UPDATED_METER)
                .tag("provider", "provider2-xml").counter();
        Counter inserted = registry.find(IngestMetrics.INSERTED_METER)
                .tag("provider", "provider2-xml").counter();

        assertThat(updated.count()).isEqualTo(1.0);
        assertThat(inserted.count()).isZero();
    }

    @Test
    @DisplayName("recordRejected uses both provider and reason tags (REQ 1.6)")
    void recordRejectedTagsBothProviderAndReason() {
        metrics.recordRejected("provider1-json", "title");
        metrics.recordRejected("provider1-json", "title");
        metrics.recordRejected("provider1-json", "publishedAt");
        metrics.recordRejected("provider2-xml", "title");

        Counter jsonTitle = registry.find(IngestMetrics.REJECTED_METER)
                .tag("provider", "provider1-json").tag("reason", "title").counter();
        Counter jsonPublished = registry.find(IngestMetrics.REJECTED_METER)
                .tag("provider", "provider1-json").tag("reason", "publishedAt").counter();
        Counter xmlTitle = registry.find(IngestMetrics.REJECTED_METER)
                .tag("provider", "provider2-xml").tag("reason", "title").counter();

        assertThat(jsonTitle).isNotNull();
        assertThat(jsonTitle.count()).isEqualTo(2.0);
        assertThat(jsonPublished).isNotNull();
        assertThat(jsonPublished.count()).isEqualTo(1.0);
        assertThat(xmlTitle).isNotNull();
        assertThat(xmlTitle.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("recordRejected substitutes 'unknown' for null reason (REQ 1.6)")
    void recordRejectedNullReasonBecomesUnknown() {
        metrics.recordRejected("provider1-json", null);

        Counter unknown = registry.find(IngestMetrics.REJECTED_METER)
                .tag("provider", "provider1-json").tag("reason", "unknown").counter();
        assertThat(unknown).isNotNull();
        assertThat(unknown.count()).isEqualTo(1.0);
    }
}
