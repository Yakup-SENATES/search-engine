package com.example.searchengine.application.scheduler;

import com.example.searchengine.application.ingest.ContentAggregator;
import com.example.searchengine.infrastructure.sync.SyncCoordinator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically triggers the {@link ContentAggregator} to refresh content from
 * all configured providers.
 * <p>
 * Scheduling is disabled when {@code aggregator.sync.enabled=false} (REQ 11.5).
 * Overlapping runs are prevented via the shared {@link SyncCoordinator}, which
 * also guards the manual-sync admin endpoint so the "already running"
 * semantics stay consistent across triggers (operability quick-wins
 * Requirement 3.2 and design Property 4).
 */
@Component
@ConditionalOnProperty(name = "aggregator.sync.enabled", matchIfMissing = true)
public class SyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(SyncScheduler.class);

    private final ContentAggregator contentAggregator;
    private final SyncCoordinator syncCoordinator;

    public SyncScheduler(ContentAggregator contentAggregator, SyncCoordinator syncCoordinator) {
        this.contentAggregator = contentAggregator;
        this.syncCoordinator = syncCoordinator;
    }

    /**
     * Scheduled sync method. Uses fixed delay so the next run starts only after
     * the previous one completes (plus the configured delay). Overlapping runs
     * are skipped by {@link SyncCoordinator#tryRun(Runnable)}, which is shared
     * with the manual-sync admin endpoint.
     */
    @Scheduled(fixedDelayString = "${aggregator.sync.fixed-delay-ms:300000}")
    public void sync() {
        boolean started = syncCoordinator.tryRun(() -> {
            try {
                log.info("Sync run started");
                contentAggregator.runSync();
                log.info("Sync run completed successfully");
            } catch (Exception e) {
                log.error("Sync run failed with unexpected error", e);
            }
        });
        if (!started) {
            log.warn("Sync run skipped: previous run is still in progress");
        }
    }
}
