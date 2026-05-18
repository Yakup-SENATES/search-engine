package com.example.searchengine.infrastructure.sync;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Unit tests for {@link SyncCoordinator}.
 *
 * <p>Validates Requirements 3.2 (manual sync mutual exclusion) and design
 * Property 4 (SyncCoordinator mutual exclusion).</p>
 */
class SyncCoordinatorTest {

    @Test
    @DisplayName("First call returns true and runs the body")
    void firstCallReturnsTrueAndRunsTheBody() {
        SyncCoordinator coordinator = new SyncCoordinator();
        AtomicBoolean ran = new AtomicBoolean(false);

        boolean started = coordinator.tryRun(() -> ran.set(true));

        assertThat(started).isTrue();
        assertThat(ran.get()).isTrue();
        assertThat(coordinator.isRunning()).isFalse();
    }

    @Test
    @DisplayName("Concurrent second call while the first is still running returns false")
    void concurrentSecondCallReturnsFalse() throws Exception {
        SyncCoordinator coordinator = new SyncCoordinator();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger executions = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() -> coordinator.tryRun(() -> {
                executions.incrementAndGet();
                firstStarted.countDown();
                try {
                    releaseFirst.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));

            assertThat(firstStarted.await(2, TimeUnit.SECONDS))
                    .as("first runnable should have started")
                    .isTrue();
            assertThat(coordinator.isRunning()).isTrue();

            // Second concurrent caller should be skipped while first is still running.
            boolean second = coordinator.tryRun(() -> executions.incrementAndGet());
            assertThat(second).isFalse();

            releaseFirst.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(executions.get()).isEqualTo(1);
            assertThat(coordinator.isRunning()).isFalse();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("After completion isRunning is false and a fresh tryRun succeeds")
    void afterCompletionFreshTryRunSucceeds() {
        SyncCoordinator coordinator = new SyncCoordinator();
        AtomicInteger executions = new AtomicInteger(0);

        boolean first = coordinator.tryRun(executions::incrementAndGet);
        assertThat(first).isTrue();
        assertThat(coordinator.isRunning()).isFalse();

        boolean second = coordinator.tryRun(executions::incrementAndGet);
        assertThat(second).isTrue();
        assertThat(coordinator.isRunning()).isFalse();

        assertThat(executions.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("Exception inside the runnable propagates and the running flag is reset")
    void exceptionPropagatesAndResetsFlag() {
        SyncCoordinator coordinator = new SyncCoordinator();
        RuntimeException boom = new RuntimeException("boom");

        assertThatThrownBy(() -> coordinator.tryRun(() -> {
            throw boom;
        })).isSameAs(boom);

        assertThat(coordinator.isRunning())
                .as("running flag must reset after the body throws")
                .isFalse();

        // A subsequent run still works.
        AtomicBoolean ran = new AtomicBoolean(false);
        boolean started = coordinator.tryRun(() -> ran.set(true));
        assertThat(started).isTrue();
        assertThat(ran.get()).isTrue();
    }

    @Test
    @DisplayName("tryRun rejects a null body with NullPointerException")
    void rejectsNullBody() {
        SyncCoordinator coordinator = new SyncCoordinator();
        assertThatNullPointerException().isThrownBy(() -> coordinator.tryRun(null));
        assertThat(coordinator.isRunning()).isFalse();
    }
}
