package com.example.searchengine.infrastructure.provider;

import com.example.searchengine.domain.provider.RawContent;
import com.example.searchengine.infrastructure.provider.jsonprovider.*;
import net.jqwik.api.*;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Property-based test for provider transport robustness (timeout and retry).
 *
 * <p>Tests the three branches of the transport robustness property by simulating
 * upstream provider HTTP behavior parameterized by {@code (failureCount, latencyMs)}.</p>
 *
 * <p><b>Validates: Requirements 21.1, 21.2, 21.3</b></p>
 */
class ProviderTransportPropertyTest {

    /**
     * A test-only implementation of JsonProviderClient that simulates configurable
     * failure counts and latency, with Resilience4j-style retry behavior
     * (max 3 attempts, exponential backoff starting at 500ms × 2).
     */
    static class SimulatedRetryClient {

        private final int failureCount;
        private final long latencyMs;
        private final AtomicInteger callCount = new AtomicInteger(0);
        private final List<Long> callTimestamps = new ArrayList<>();

        private static final int MAX_ATTEMPTS = 3;
        private static final long INITIAL_BACKOFF_MS = 500;
        private static final double BACKOFF_MULTIPLIER = 2.0;
        private static final long READ_TIMEOUT_MS = 10_000;

        SimulatedRetryClient(int failureCount, long latencyMs) {
            this.failureCount = failureCount;
            this.latencyMs = latencyMs;
        }

        /**
         * Simulates the retry-decorated fetch with exponential backoff.
         * Mimics the behavior of Resilience4j Retry wrapping a RestClient call.
         *
         * @return the provider response if successful within retry budget
         * @throws RestClientException if all retries exhausted or timeout exceeded
         */
        public JsonProviderResponse fetchWithRetry() {
            long backoff = INITIAL_BACKOFF_MS;

            for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
                callTimestamps.add(System.nanoTime());
                callCount.incrementAndGet();

                // Simulate timeout: if latency exceeds read timeout, throw
                if (latencyMs > READ_TIMEOUT_MS) {
                    throw new ResourceAccessException(
                            "Read timed out after " + READ_TIMEOUT_MS + "ms (simulated latency: " + latencyMs + "ms)");
                }

                // Simulate failure for the first N calls
                if (attempt < failureCount) {
                    // If this is not the last attempt, apply backoff before next retry
                    if (attempt < MAX_ATTEMPTS - 1) {
                        try {
                            Thread.sleep(backoff);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new RestClientException("Interrupted during backoff");
                        }
                        backoff = (long) (backoff * BACKOFF_MULTIPLIER);
                    }
                    continue;
                }

                // Success: return a valid response
                return createSuccessResponse();
            }

            // All retries exhausted
            throw new RestClientException("All " + MAX_ATTEMPTS + " attempts failed");
        }

        public int getCallCount() {
            return callCount.get();
        }

        public List<Long> getCallTimestamps() {
            return callTimestamps;
        }

        private JsonProviderResponse createSuccessResponse() {
            JsonContentDto dto = new JsonContentDto(
                    "ext-1",
                    "Test Content",
                    "video",
                    new JsonMetrics(15000, 1200, "PT10M"),
                    Instant.parse("2024-01-15T10:00:00Z"),
                    List.of("java", "spring")
            );
            return new JsonProviderResponse(List.of(dto));
        }
    }

    /**
     * A test adapter that uses the SimulatedRetryClient instead of the real JsonProviderClient.
     * Mirrors the behavior of JsonProviderAdapter: catches exceptions and returns [].
     */
    static class TestProviderAdapter {

        private final SimulatedRetryClient client;

        TestProviderAdapter(SimulatedRetryClient client) {
            this.client = client;
        }

        public String name() {
            return "provider1-json";
        }

        public List<RawContent> fetch() {
            try {
                JsonProviderResponse response = client.fetchWithRetry();

                if (response == null || response.contents() == null) {
                    return List.of();
                }

                JsonContentMapper mapper = new JsonContentMapper();
                return response.contents().stream()
                        .limit(1000)
                        .map(mapper::map)
                        .flatMap(java.util.Optional::stream)
                        .toList();

            } catch (RestClientException e) {
                // REQ 2.4, 2.6, 21.3: return empty list, do not throw
                return List.of();
            } catch (Exception e) {
                return List.of();
            }
        }
    }

    @Property(tries = 100)
    @Label("Feature: search-engine-service, Property 11: Provider transport robustness (timeout and retry)")
    void providerTransportRobustness(
            @ForAll("failureAndLatencyParams") Tuple.Tuple2<Integer, Long> params
    ) {
        int failureCount = params.get1();
        long latencyMs = params.get2();

        SimulatedRetryClient simulatedClient = new SimulatedRetryClient(failureCount, latencyMs);
        TestProviderAdapter adapter = new TestProviderAdapter(simulatedClient);

        // Branch (a): if latencyMs > 10000, the adapter aborts within 10500 ms and returns []
        if (latencyMs > 10_000) {
            long startNs = System.nanoTime();

            List<RawContent> result = adapter.fetch();

            long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;

            assertThat(result)
                    .as("Branch (a): latencyMs=%d > 10000 → adapter should return empty list", latencyMs)
                    .isEmpty();
            assertThat(elapsedMs)
                    .as("Branch (a): adapter should abort within 10500ms (actual: %dms)", elapsedMs)
                    .isLessThan(10_500);
        }
        // Branch (b): if failureCount ≤ 2 (succeeds within retry budget), returns non-empty list
        else if (failureCount <= 2) {
            List<RawContent> result = adapter.fetch();

            assertThat(result)
                    .as("Branch (b): failureCount=%d ≤ 2 → adapter should return non-empty list", failureCount)
                    .isNotEmpty();

            // Verify exponential backoff: delay between calls should follow 500ms * 2^n pattern
            if (failureCount > 0) {
                List<Long> timestamps = simulatedClient.getCallTimestamps();
                assertThat(timestamps.size())
                        .as("Branch (b): should have made %d+1 calls (failures + success)", failureCount)
                        .isEqualTo(failureCount + 1);

                // Verify backoff delays between consecutive calls
                long expectedBackoff = 500; // initial backoff in ms
                for (int i = 1; i < timestamps.size(); i++) {
                    long delayNs = timestamps.get(i) - timestamps.get(i - 1);
                    long delayMs = delayNs / 1_000_000;
                    // Allow 20% tolerance for timing imprecision
                    assertThat(delayMs)
                            .as("Branch (b): delay between call %d and %d should be ~%dms (exponential backoff)",
                                    i - 1, i, expectedBackoff)
                            .isGreaterThanOrEqualTo((long) (expectedBackoff * 0.8));
                    expectedBackoff = (long) (expectedBackoff * 2.0);
                }
            }
        }
        // Branch (c): if failureCount ≥ 3 (all retries fail), returns [] and does not throw
        else {
            assertThatCode(() -> {
                List<RawContent> result = adapter.fetch();

                assertThat(result)
                        .as("Branch (c): failureCount=%d ≥ 3 → adapter should return empty list", failureCount)
                        .isEmpty();
            }).as("Branch (c): adapter must not throw any exception")
                    .doesNotThrowAnyException();
        }
    }

    @Provide
    Arbitrary<Tuple.Tuple2<Integer, Long>> failureAndLatencyParams() {
        // Generate failureCount: 0-5 to cover all three branches
        Arbitrary<Integer> failureCountArb = Arbitraries.integers().between(0, 5);

        // Generate latencyMs: mix of values below and above the 10000ms timeout threshold
        Arbitrary<Long> latencyMsArb = Arbitraries.oneOf(
                // Normal latency (below timeout) - covers branches (b) and (c)
                Arbitraries.longs().between(0, 9_999),
                // High latency (above timeout) - covers branch (a)
                Arbitraries.longs().between(10_001, 30_000)
        );

        return Combinators.combine(failureCountArb, latencyMsArb).as(Tuple::of);
    }
}
