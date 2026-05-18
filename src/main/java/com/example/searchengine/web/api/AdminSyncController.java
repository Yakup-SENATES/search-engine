package com.example.searchengine.web.api;

import com.example.searchengine.application.ingest.ContentAggregator;
import com.example.searchengine.infrastructure.sync.SyncCoordinator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin REST controller for triggering a manual content sync.
 *
 * <p>Dispatches the sync on a dedicated {@link TaskExecutor} so the HTTP request
 * returns immediately with HTTP 202 Accepted. The {@link SyncCoordinator}'s
 * {@code AtomicBoolean} guard ensures at most one sync runs at a time — if a
 * sync is already in progress, the endpoint reports {@code alreadyRunning=true}
 * without starting a second run (REQ 3.1, 3.2).</p>
 *
 * <p>Unexpected exceptions are left to the {@code GlobalExceptionHandler} which
 * produces the standardized 500 {@code INTERNAL_ERROR} envelope (REQ 3.3).</p>
 */
@RestController
@RequestMapping("/api/v1/admin/sync")
public class AdminSyncController {

    private static final Logger log = LoggerFactory.getLogger(AdminSyncController.class);

    private final SyncCoordinator coordinator;
    private final ContentAggregator contentAggregator;
    private final TaskExecutor adminSyncExecutor;

    public AdminSyncController(SyncCoordinator coordinator,
                               ContentAggregator contentAggregator,
                               @Qualifier("adminSyncExecutor") TaskExecutor adminSyncExecutor) {
        this.coordinator = coordinator;
        this.contentAggregator = contentAggregator;
        this.adminSyncExecutor = adminSyncExecutor;
    }

    /**
     * Triggers a manual sync run.
     *
     * <p>If a sync is already in progress, returns immediately with
     * {@code triggered=false, alreadyRunning=true}. Otherwise, submits the sync
     * to the background executor and returns {@code triggered=true,
     * alreadyRunning=false}.</p>
     *
     * @return HTTP 202 with the {@link ManualSyncResponse} envelope
     */
    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ManualSyncResponse> triggerSync() {
        if (coordinator.isRunning()) {
            log.info("Manual sync skipped — already running");
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(ManualSyncResponse.syncAlreadyRunning());
        }

        adminSyncExecutor.execute(() -> {
            log.info("Manual sync dispatched on background thread");
            coordinator.tryRun(contentAggregator::runSync);
        });

        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ManualSyncResponse.syncTriggered());
    }
}
