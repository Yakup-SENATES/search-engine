package com.example.searchengine.infrastructure.sync;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Provides a dedicated {@link TaskExecutor} for the admin manual-sync endpoint.
 *
 * <p>The executor is configured with a single worker thread so that at most one
 * sync run can be dispatched at a time. The {@code SyncCoordinator}'s
 * {@code AtomicBoolean} guard provides the mutual-exclusion semantics; this
 * executor simply ensures the HTTP request thread is not blocked while the sync
 * runs (REQ 3.1).</p>
 */
@Configuration
public class AdminSyncConfig {

    /**
     * Single-threaded executor used by {@code AdminSyncController} to dispatch
     * sync runs off the request thread.
     */
    @Bean("adminSyncExecutor")
    public TaskExecutor adminSyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.setThreadNamePrefix("admin-sync-");
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.DiscardPolicy());
        executor.initialize();
        return executor;
    }
}
