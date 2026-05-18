package com.example.searchengine.infrastructure.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Records ingest-pipeline outcome counters per provider.
 *
 * <p>Meter taxonomy (also surfaced through {@code /actuator/prometheus}):
 * <ul>
 *   <li>{@value #INSERTED_METER} (Counter) tagged {@code provider} — bumped
 *       once per item that produced an {@code INSERTED} upsert outcome.</li>
 *   <li>{@value #UPDATED_METER} (Counter) tagged {@code provider} — bumped
 *       once per item that produced an {@code UPDATED} upsert outcome.</li>
 *   <li>{@value #REJECTED_METER} (Counter) tagged {@code provider, reason}
 *       — bumped once per item rejected during normalization, where
 *       {@code reason} is the offending field name reported by the
 *       normalizer (e.g. {@code title}, {@code publishedAt}).</li>
 * </ul>
 *
 * <p>Counters for the two shipped providers ({@code provider1-json} and
 * {@code provider2-xml}) are eagerly registered in the constructor so
 * dashboards see all four insert / update series even before the first
 * sync run lands. The reject counter is allocated lazily on first use
 * because its tag space is unbounded by reason.</p>
 *
 * <p>Tag values are restricted to the static provider identifier and the
 * normalizer-supplied {@code field} name. User-supplied request data
 * never reaches the registry (REQ 1.8).</p>
 *
 * <p>Validates: Requirements 1.6, 1.8 (operability quick-wins).</p>
 */
@Component
public class IngestMetrics {

    /** Name of the Counter incremented for each newly inserted content row. */
    public static final String INSERTED_METER = "ingest_items_inserted_total";

    /** Name of the Counter incremented for each updated existing content row. */
    public static final String UPDATED_METER = "ingest_items_updated_total";

    /** Name of the Counter incremented for each item rejected during normalization. */
    public static final String REJECTED_METER = "ingest_items_rejected_total";

    static final String TAG_PROVIDER = "provider";
    static final String TAG_REASON = "reason";

    /** Default reason used when the rejection field is absent or blank. */
    static final String REASON_UNKNOWN = "unknown";

    /**
     * Names of the providers shipped with the service. Used to eagerly
     * register the {@link #INSERTED_METER} / {@link #UPDATED_METER} counters
     * so dashboards see them before the first sync completes.
     */
    private static final String[] SHIPPED_PROVIDERS = {"provider1-json", "provider2-xml"};

    private final MeterRegistry registry;

    public IngestMetrics(MeterRegistry registry) {
        this.registry = registry;
        for (String provider : SHIPPED_PROVIDERS) {
            insertedCounter(provider);
            updatedCounter(provider);
        }
    }

    /**
     * Increments {@value #INSERTED_METER} for the given provider.
     *
     * @param providerName static {@code ContentProvider.name()} value
     */
    public void recordInserted(String providerName) {
        insertedCounter(providerName).increment();
    }

    /**
     * Increments {@value #UPDATED_METER} for the given provider.
     *
     * @param providerName static {@code ContentProvider.name()} value
     */
    public void recordUpdated(String providerName) {
        updatedCounter(providerName).increment();
    }

    /**
     * Increments {@value #REJECTED_METER} for the given provider and reason.
     * A {@code null} or blank reason is normalised to {@value #REASON_UNKNOWN}
     * to keep the tag space bounded.
     *
     * @param providerName static {@code ContentProvider.name()} value
     * @param reason       the normalizer-supplied field name (or {@code null})
     */
    public void recordRejected(String providerName, String reason) {
        String safeReason = (reason == null || reason.isBlank()) ? REASON_UNKNOWN : reason;
        Counter.builder(REJECTED_METER)
                .description("Number of ingested items rejected during normalization")
                .tag(TAG_PROVIDER, providerName)
                .tag(TAG_REASON, safeReason)
                .register(registry)
                .increment();
    }

    private Counter insertedCounter(String providerName) {
        return Counter.builder(INSERTED_METER)
                .description("Number of ingested items that were inserted as new rows")
                .tag(TAG_PROVIDER, providerName)
                .register(registry);
    }

    private Counter updatedCounter(String providerName) {
        return Counter.builder(UPDATED_METER)
                .description("Number of ingested items that updated existing rows")
                .tag(TAG_PROVIDER, providerName)
                .register(registry);
    }
}
