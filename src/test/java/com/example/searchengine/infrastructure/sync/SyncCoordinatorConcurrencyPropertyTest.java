package com.example.searchengine.infrastructure.sync;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based concurrency test for {@link SyncCoordinator}.
 *
 * <p><b>Validates: Requirements 3.2</b> — and design Property 4
 * (SyncCoordinator mutual exclusion).</p>
 *
 * <p>For any number of concurrent threads racing against a single
 * {@link SyncCoordinator}, at most one runnable is executing at any given
 * instant, the count of accepted invocations equals the number of executed
 * bodies, and after all threads finish the coordinator's
 * {@code isRunning()} flag returns {@code false}.</p>
 */
class SyncCoordinatorConcurrencyPropertyTest {

    @Property(tries = 200)
    @Label("Feature: operability-quick-wins, Property 4: SyncCoordinator mutual exclusion")
    void atMostOneRunnableExecutesConcurrently(@ForAll("scenarios") Scenario scenario) throws Exception {
        SyncCoordinator coordinator = new SyncCoordinator();
        AtomicInteger inFlight = new AtomicInteger(0);
        AtomicInteger maxInFlight = new AtomicInteger(0);
        AtomicInteger executions = new AtomicInteger(0);
        AtomicInteger acceptedReturns = new AtomicInteger(0);
        AtomicInteger skippedReturns = new AtomicInteger(0);

        int threads = scenario.threads();
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Boolean>> results = new ArrayList<>(threads);

            for (int i = 0; i < threads; i++) {
                final int bodyDelayNs = scenario.bodyDelayNanos();
                results.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return coordinator.tryRun(() -> {
                        int now = inFlight.incrementAndGet();
                        // Track running maximum of concurrent executions.
                        maxInFlight.updateAndGet(prev -> Math.max(prev, now));
                        try {
                            if (bodyDelayNs > 0) {
                                long deadline = System.nanoTime() + bodyDelayNs;
                                while (System.nanoTime() < deadline) {
                                    Thread.onSpinWait();
                                }
                            }
                            executions.incrementAndGet();
                        } finally {
                            inFlight.decrementAndGet();
                        }
                    });
                }));
            }

            // Wait until every worker has been scheduled, then release them at once
            // to maximize the chance of a real race.
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            for (Future<Boolean> f : results) {
                Boolean accepted = f.get(10, TimeUnit.SECONDS);
                if (Boolean.TRUE.equals(accepted)) {
                    acceptedReturns.incrementAndGet();
                } else {
                    skippedReturns.incrementAndGet();
                }
            }
        } finally {
            executor.shutdownNow();
        }

        // Property 4: at most one runnable executes concurrently.
        assertThat(maxInFlight.get())
                .as("at most one runnable should ever be in-flight")
                .isLessThanOrEqualTo(1);

        // Accepted returns equal observed body executions.
        assertThat(executions.get())
                .as("number of executed bodies equals number of accepted tryRun calls")
                .isEqualTo(acceptedReturns.get());

        // Every caller saw either an accepted or a skipped result.
        assertThat(acceptedReturns.get() + skippedReturns.get()).isEqualTo(threads);

        // After every worker finished, the coordinator must be free again.
        assertThat(coordinator.isRunning())
                .as("running flag must be cleared after all workers finish")
                .isFalse();
    }

    @Provide
    Arbitrary<Scenario> scenarios() {
        Arbitrary<Integer> threadCounts = Arbitraries.integers().between(1, 8);
        // Tiny busy-wait windows (0 ns to ~50 µs) keep the test fast while still
        // exposing the race between threads.
        Arbitrary<Integer> bodyDelays = Arbitraries.integers().between(0, 50_000);
        return Combinators.combine(threadCounts, bodyDelays).as(Scenario::new);
    }

    /** Single random scenario for one property iteration. */
    record Scenario(int threads, int bodyDelayNanos) { }
}
