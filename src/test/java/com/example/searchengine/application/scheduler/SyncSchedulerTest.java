package com.example.searchengine.application.scheduler;

import com.example.searchengine.application.ingest.ContentAggregator;
import com.example.searchengine.infrastructure.sync.SyncCoordinator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link SyncScheduler} verifying it correctly delegates the
 * mutual-exclusion guard to the shared {@link SyncCoordinator}.
 *
 * <p>Validates Requirement 3.2 (overlapping run guard semantics shared
 * between scheduled and manual triggers) and design Property 4
 * (SyncCoordinator mutual exclusion).</p>
 */
class SyncSchedulerTest {

    @Test
    @DisplayName("First scheduled tick runs the aggregator exactly once")
    void firstScheduledTickRunsAggregatorOnce() {
        ContentAggregator aggregator = mock(ContentAggregator.class);
        SyncCoordinator coordinator = new SyncCoordinator();
        SyncScheduler scheduler = new SyncScheduler(aggregator, coordinator);

        scheduler.sync();

        verify(aggregator, times(1)).runSync();
        assertThat(coordinator.isRunning()).isFalse();
    }

    @Test
    @DisplayName("A concurrent overlapping tick is skipped while a sync is running")
    void overlappingScheduledTickIsSkipped() throws Exception {
        AtomicInteger calls = new AtomicInteger(0);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        ContentAggregator aggregator = () -> {
            calls.incrementAndGet();
            firstStarted.countDown();
            try {
                releaseFirst.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        SyncCoordinator coordinator = new SyncCoordinator();
        SyncScheduler scheduler = new SyncScheduler(aggregator, coordinator);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> first = executor.submit(scheduler::sync);

            assertThat(firstStarted.await(2, TimeUnit.SECONDS))
                    .as("first scheduled tick should have started the aggregator")
                    .isTrue();
            assertThat(coordinator.isRunning()).isTrue();

            // A second overlapping scheduled tick must be skipped.
            scheduler.sync();
            assertThat(calls.get()).isEqualTo(1);

            releaseFirst.countDown();
            first.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(coordinator.isRunning()).isFalse();
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Aggregator exception is swallowed and the running flag is reset")
    void aggregatorExceptionResetsRunningFlag() {
        ContentAggregator aggregator = () -> {
            throw new RuntimeException("boom");
        };
        SyncCoordinator coordinator = new SyncCoordinator();
        SyncScheduler scheduler = new SyncScheduler(aggregator, coordinator);

        scheduler.sync(); // must not throw

        assertThat(coordinator.isRunning()).isFalse();
    }

    @Test
    @DisplayName("Subsequent scheduled tick after completion runs the aggregator again")
    void subsequentTickAfterCompletionRunsAggregatorAgain() {
        ContentAggregator aggregator = mock(ContentAggregator.class);
        SyncCoordinator coordinator = new SyncCoordinator();
        SyncScheduler scheduler = new SyncScheduler(aggregator, coordinator);

        scheduler.sync();
        scheduler.sync();

        verify(aggregator, times(2)).runSync();
        assertThat(coordinator.isRunning()).isFalse();
    }
}
