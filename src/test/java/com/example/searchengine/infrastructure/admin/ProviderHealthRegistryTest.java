package com.example.searchengine.infrastructure.admin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ProviderHealthRegistry}.
 *
 * <p>Validates: Requirements 2.1–2.5 (operability quick-wins).</p>
 */
class ProviderHealthRegistryTest {

    private static final Instant FIXED_NOW = Instant.parse("2024-08-01T12:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);

    private ProviderHealthRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ProviderHealthRegistry(FIXED_CLOCK);
    }

    // ─── Initial state ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("Initial state has no snapshots")
    void initialState_noSnapshots() {
        Map<String, ProviderHealthSnapshot> snapshots = registry.getSnapshots();
        assertThat(snapshots).isEmpty();
    }

    // ─── recordSuccess ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("recordSuccess creates a snapshot with correct fields")
    void recordSuccess_createsSnapshot() {
        registry.recordSuccess("provider1-json", 42);

        Map<String, ProviderHealthSnapshot> snapshots = registry.getSnapshots();
        assertThat(snapshots).containsKey("provider1-json");

        ProviderHealthSnapshot snapshot = snapshots.get("provider1-json");
        assertThat(snapshot.name()).isEqualTo("provider1-json");
        assertThat(snapshot.lastSyncAt()).isEqualTo(FIXED_NOW);
        assertThat(snapshot.lastSyncOutcome()).isEqualTo("success");
        assertThat(snapshot.lastFetchedItems()).isEqualTo(42);
        assertThat(snapshot.totalSuccesses()).isEqualTo(1L);
        assertThat(snapshot.totalFailures()).isEqualTo(0L);
        assertThat(snapshot.lastErrorMessage()).isNull();
    }

    @Test
    @DisplayName("recordSuccess increments totalSuccesses on repeated calls")
    void recordSuccess_incrementsTotalSuccesses() {
        registry.recordSuccess("provider1-json", 10);
        registry.recordSuccess("provider1-json", 20);
        registry.recordSuccess("provider1-json", 30);

        ProviderHealthSnapshot snapshot = registry.getSnapshots().get("provider1-json");
        assertThat(snapshot.totalSuccesses()).isEqualTo(3L);
        assertThat(snapshot.lastFetchedItems()).isEqualTo(30);
    }

    // ─── recordFailure ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("recordFailure creates a snapshot with correct fields")
    void recordFailure_createsSnapshot() {
        RuntimeException error = new RuntimeException("Connection refused");
        registry.recordFailure("provider2-xml", error);

        Map<String, ProviderHealthSnapshot> snapshots = registry.getSnapshots();
        assertThat(snapshots).containsKey("provider2-xml");

        ProviderHealthSnapshot snapshot = snapshots.get("provider2-xml");
        assertThat(snapshot.name()).isEqualTo("provider2-xml");
        assertThat(snapshot.lastSyncAt()).isEqualTo(FIXED_NOW);
        assertThat(snapshot.lastSyncOutcome()).isEqualTo("failure");
        assertThat(snapshot.totalSuccesses()).isEqualTo(0L);
        assertThat(snapshot.totalFailures()).isEqualTo(1L);
        assertThat(snapshot.lastErrorMessage()).isEqualTo("java.lang.RuntimeException: Connection refused");
    }

    @Test
    @DisplayName("recordFailure truncates error message to 256 characters")
    void recordFailure_truncatesErrorMessage() {
        // Create an exception with a message longer than 256 chars
        String longMessage = "A".repeat(300);
        RuntimeException error = new RuntimeException(longMessage);

        registry.recordFailure("provider1-json", error);

        ProviderHealthSnapshot snapshot = registry.getSnapshots().get("provider1-json");
        assertThat(snapshot.lastErrorMessage()).hasSize(256);
    }

    @Test
    @DisplayName("recordFailure increments totalFailures on repeated calls")
    void recordFailure_incrementsTotalFailures() {
        registry.recordFailure("provider1-json", new RuntimeException("err1"));
        registry.recordFailure("provider1-json", new RuntimeException("err2"));

        ProviderHealthSnapshot snapshot = registry.getSnapshots().get("provider1-json");
        assertThat(snapshot.totalFailures()).isEqualTo(2L);
        assertThat(snapshot.totalSuccesses()).isEqualTo(0L);
    }

    // ─── Multiple providers tracked independently ────────────────────────────────

    @Test
    @DisplayName("Multiple providers are tracked independently")
    void multipleProviders_trackedIndependently() {
        registry.recordSuccess("provider1-json", 10);
        registry.recordFailure("provider2-xml", new RuntimeException("timeout"));
        registry.recordSuccess("provider1-json", 15);

        Map<String, ProviderHealthSnapshot> snapshots = registry.getSnapshots();
        assertThat(snapshots).hasSize(2);

        ProviderHealthSnapshot json = snapshots.get("provider1-json");
        assertThat(json.totalSuccesses()).isEqualTo(2L);
        assertThat(json.totalFailures()).isEqualTo(0L);
        assertThat(json.lastSyncOutcome()).isEqualTo("success");

        ProviderHealthSnapshot xml = snapshots.get("provider2-xml");
        assertThat(xml.totalSuccesses()).isEqualTo(0L);
        assertThat(xml.totalFailures()).isEqualTo(1L);
        assertThat(xml.lastSyncOutcome()).isEqualTo("failure");
    }

    // ─── Success after failure clears error message ──────────────────────────────

    @Test
    @DisplayName("Success after failure clears lastErrorMessage")
    void successAfterFailure_clearsErrorMessage() {
        registry.recordFailure("provider1-json", new RuntimeException("oops"));
        registry.recordSuccess("provider1-json", 5);

        ProviderHealthSnapshot snapshot = registry.getSnapshots().get("provider1-json");
        assertThat(snapshot.lastSyncOutcome()).isEqualTo("success");
        assertThat(snapshot.lastErrorMessage()).isNull();
        assertThat(snapshot.totalSuccesses()).isEqualTo(1L);
        assertThat(snapshot.totalFailures()).isEqualTo(1L);
    }

    // ─── Concurrent access safety ───────────────────────────────────────────────

    @Test
    @DisplayName("Concurrent access does not lose updates")
    void concurrentAccess_noLostUpdates() throws InterruptedException {
        int threadCount = 50;
        int iterationsPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < iterationsPerThread; j++) {
                        if (threadId % 2 == 0) {
                            registry.recordSuccess("concurrent-provider", 1);
                        } else {
                            registry.recordFailure("concurrent-provider", new RuntimeException("err"));
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();

        ProviderHealthSnapshot snapshot = registry.getSnapshots().get("concurrent-provider");
        assertThat(snapshot).isNotNull();

        // 25 threads do success (100 each) = 2500, 25 threads do failure (100 each) = 2500
        long expectedSuccesses = (threadCount / 2) * (long) iterationsPerThread;
        long expectedFailures = (threadCount / 2) * (long) iterationsPerThread;

        assertThat(snapshot.totalSuccesses()).isEqualTo(expectedSuccesses);
        assertThat(snapshot.totalFailures()).isEqualTo(expectedFailures);
    }

    // ─── getSnapshots returns unmodifiable view ──────────────────────────────────

    @Test
    @DisplayName("getSnapshots returns an unmodifiable map")
    void getSnapshots_unmodifiable() {
        registry.recordSuccess("provider1-json", 5);
        Map<String, ProviderHealthSnapshot> snapshots = registry.getSnapshots();

        org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> snapshots.put("hacker", ProviderHealthSnapshot.initial("hacker"))
        );
    }

    // ─── recordFailure with null throwable ───────────────────────────────────────

    @Test
    @DisplayName("recordFailure with null throwable uses 'unknown error' message")
    void recordFailure_nullThrowable_usesUnknownError() {
        registry.recordFailure("provider1-json", null);

        ProviderHealthSnapshot snapshot = registry.getSnapshots().get("provider1-json");
        assertThat(snapshot.lastErrorMessage()).isEqualTo("unknown error");
    }
}
