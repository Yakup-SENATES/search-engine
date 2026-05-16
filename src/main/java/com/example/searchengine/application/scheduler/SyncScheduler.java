package com.example.searchengine.application.scheduler;

import com.example.searchengine.application.ingest.ContentAggregator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Periodically triggers the {@link ContentAggregator} to refresh content from
 * all configured providers.
 * <p>
 * Scheduling is disabled when {@code aggregator.sync.enabled=false} (REQ 11.5).
 * Overlapping runs are prevented via an {@link AtomicBoolean} guard.
 */
@Component
@ConditionalOnProperty(name = "aggregator.sync.enabled", matchIfMissing = true)
public class SyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(SyncScheduler.class);

    private final ContentAggregator contentAggregator;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public SyncScheduler(ContentAggregator contentAggregator) {
        this.contentAggregator = contentAggregator;
    }

    /**
     * Scheduled sync method. Uses fixed delay so the next run starts only after
     * the previous one completes (plus the configured delay). Overlapping runs
     * are skipped if a previous invocation is still in progress.
     */
    @Scheduled(fixedDelayString = "${aggregator.sync.fixed-delay-ms:300000}")
    public void sync() {
        if (!running.compareAndSet(false, true)) {
            log.warn("Sync run skipped: previous run is still in progress");
            return;
        }

        try {
            log.info("Sync run started");
            contentAggregator.runSync();
            log.info("Sync run completed successfully");
        } catch (Exception e) {
            log.error("Sync run failed with unexpected error", e);
        } finally {
            running.set(false);
        }
    }
}
