package com.example.searchengine.infrastructure.metrics;

import com.example.searchengine.application.search.DefaultSearchService;
import com.example.searchengine.application.search.SearchQuery;
import com.example.searchengine.domain.content.Content;
import com.example.searchengine.domain.content.ContentRepository;
import com.example.searchengine.domain.content.ContentType;
import com.example.searchengine.domain.content.SearchCriteria;
import com.example.searchengine.domain.content.SearchPage;
import com.example.searchengine.domain.content.SortField;
import com.example.searchengine.domain.content.UpsertOutcome;
import com.example.searchengine.infrastructure.cache.SearchCacheKeyGenerator;
import com.example.searchengine.infrastructure.ratelimit.ClientIpResolver;
import com.example.searchengine.infrastructure.ratelimit.RateLimitFilter;
import com.example.searchengine.infrastructure.ratelimit.RateLimitProperties;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import jakarta.servlet.FilterChain;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import org.slf4j.MDC;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property test asserting no end-user PII leaks into Prometheus metric tag
 * values exposed via {@code /actuator/prometheus}.
 *
 * <p><b>Validates: Requirements 1.8 (operability quick-wins).</b></p>
 *
 * <p>For each iteration the test generates:
 * <ul>
 *   <li>a random search keyword {@code q} (16+ alphanumeric chars — long
 *       enough that no structural tag value such as {@code success},
 *       {@code provider1-json}, or {@code /api/v1/} can contain it as a
 *       substring),</li>
 *   <li>a random {@code requestId} (UUID — same shape as the value the
 *       {@code RequestIdFilter} would propagate via MDC),</li>
 *   <li>a random IPv4 or IPv6 literal (the value the rate-limit filter
 *       resolves from {@code X-Forwarded-For}/{@code remoteAddr}).</li>
 * </ul>
 *
 * <p>It then drives traffic through every Micrometer collaborator introduced
 * by this feature against a fresh {@link PrometheusMeterRegistry}:
 * <ul>
 *   <li>{@link SearchMetrics} — via a real {@link DefaultSearchService}
 *       running the random {@code q} through the search pipeline.</li>
 *   <li>{@link RateLimitMetrics} — via a real {@link RateLimitFilter} fed
 *       requests originating from the random IP literal until its budget is
 *       exhausted and a 429 is emitted.</li>
 *   <li>{@link IngestMetrics} — by recording an inserted, an updated, and a
 *       rejected ingest outcome.</li>
 *   <li>{@link ProviderFetchMetrics} — by recording a successful and a failed
 *       fetch sample.</li>
 * </ul>
 *
 * <p>After the drivers run, the test iterates every {@link Meter} registered
 * in the Prometheus registry and asserts each {@link Tag#getValue()}:
 * <ol>
 *   <li>does not match an IPv4 / IPv6 literal pattern,</li>
 *   <li>does not match a UUID shape (would indicate a leaked {@code requestId}),</li>
 *   <li>does not contain the generated {@code q}, {@code requestId}, or IP
 *       literal as a substring.</li>
 * </ol>
 * The well-known fixed tag values ({@code success}, {@code failure},
 * {@code true}, {@code false}, the static API path prefix, the static provider
 * names, and the normalizer-supplied rejection field names) are bounded by
 * design and trivially pass these assertions.</p>
 *
 * <p>jqwik runs the property for 200 iterations.</p>
 */
class MetricsPiiPropertyTest {

    /** Loose IPv4 literal pattern: four 1–3 digit groups separated by dots. */
    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");

    /** Loose IPv6 literal pattern: hex digits and colons only. */
    private static final Pattern IPV6_LITERAL = Pattern.compile("^[0-9a-fA-F:]+$");

    /** UUID shape (used for requestId leak detection). */
    private static final Pattern UUID_SHAPE = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    @Property(tries = 200)
    @Label("Feature: operability-quick-wins, Property 1: No PII in metrics")
    void noPiiInMeterTagValues(@ForAll("randomQueries") String q,
                               @ForAll("randomIps") String ip,
                               @ForAll @IntRange(min = 1, max = 5) int budget) {
        String requestId = UUID.randomUUID().toString();

        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

        // Build all four metric components against a fresh registry so each
        // iteration starts from a clean slate.
        ProviderFetchMetrics providerFetchMetrics = new ProviderFetchMetrics(registry);
        SearchMetrics searchMetrics = new SearchMetrics(registry);
        IngestMetrics ingestMetrics = new IngestMetrics(registry);
        RateLimitMetrics rateLimitMetrics = new RateLimitMetrics(registry);

        // Make sure the request-id MDC value is in scope for the duration of the
        // iteration — the same way RequestIdFilter would set it on a real request.
        MDC.put("requestId", requestId);
        try {
            exerciseSearchPath(searchMetrics, q);
            exerciseRateLimitPath(rateLimitMetrics, ip, budget);
            exerciseIngestPath(ingestMetrics);
            exerciseProviderFetchPath(providerFetchMetrics);
        } finally {
            MDC.remove("requestId");
        }

        // Sanity: at least our four collaborators registered something.
        assertThat(registry.getMeters())
                .as("metric components must register at least one meter each")
                .isNotEmpty();

        for (Meter meter : registry.getMeters()) {
            for (Tag tag : meter.getId().getTags()) {
                assertNotPii(meter.getId().getName(), tag, q, requestId, ip);
            }
        }

        // Belt-and-suspenders: scrape the registry to confirm the Prometheus
        // exposition format renders without PII either. We don't scan the
        // numeric sample lines (counters legitimately serialize as "1.0",
        // "0.034", etc., which would generate spurious matches against short
        // numeric substrings of the IP literal). Instead, we assert that the
        // scrape is non-empty and that it doesn't carry the requestId — a
        // 36-char hyphenated UUID — anywhere in its body.
        String scrape = registry.scrape();
        assertThat(scrape).as("scrape produced output").isNotEmpty();
        assertThat(scrape)
                .as("Prometheus scrape output must not contain the requestId")
                .doesNotContain(requestId);
    }

    private static void assertNotPii(String meterName, Tag tag,
                                     String q, String requestId, String ip) {
        String tagKey = tag.getKey();
        String value = tag.getValue();

        assertThat(IPV4.matcher(value).matches())
                .as("meter '%s' tag '%s'='%s' must not match an IPv4 literal", meterName, tagKey, value)
                .isFalse();

        if (IPV6_LITERAL.matcher(value).matches()) {
            long colons = value.chars().filter(c -> c == ':').count();
            assertThat(colons)
                    .as("meter '%s' tag '%s'='%s' must not match an IPv6 literal",
                            meterName, tagKey, value)
                    .isLessThan(2);
        }

        assertThat(UUID_SHAPE.matcher(value).matches())
                .as("meter '%s' tag '%s'='%s' must not match a UUID shape (likely a requestId)",
                        meterName, tagKey, value)
                .isFalse();

        assertThat(value)
                .as("meter '%s' tag '%s' value '%s' must not contain the submitted q='%s'",
                        meterName, tagKey, value, q)
                .doesNotContain(q);
        assertThat(value)
                .as("meter '%s' tag '%s' value '%s' must not contain the requestId='%s'",
                        meterName, tagKey, value, requestId)
                .doesNotContain(requestId);
        assertThat(value)
                .as("meter '%s' tag '%s' value '%s' must not contain the client IP='%s'",
                        meterName, tagKey, value, ip)
                .doesNotContain(ip);
    }

    // ─── Code-path drivers ───────────────────────────────────────────────────

    private static void exerciseSearchPath(SearchMetrics searchMetrics, String q) {
        ContentRepository repository = new FakeRepository();
        DefaultSearchService service = new DefaultSearchService(
                repository,
                new NoOpCacheManager(),
                new SearchCacheKeyGenerator(),
                searchMetrics);

        SearchQuery query = new SearchQuery(q, null, "score", 1, 10);
        service.search(query);
    }

    private static void exerciseRateLimitPath(RateLimitMetrics metrics, String ip, int budget) {
        RateLimitProperties props = new RateLimitProperties();
        props.setEnabled(true);
        props.setRequestsPerWindow(budget);
        props.setWindowSeconds(60L);

        RateLimitFilter filter = new RateLimitFilter(props, new ClientIpResolver(), metrics);
        FilterChain chain = (req, res) -> { };

        try {
            // Burn through the budget so the next request triggers the metrics
            // increment path.
            for (int i = 0; i < budget; i++) {
                MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/search");
                request.setRemoteAddr(ip);
                filter.doFilter(request, new MockHttpServletResponse(), chain);
            }
            MockHttpServletRequest rejected = new MockHttpServletRequest("GET", "/api/v1/search");
            rejected.setRemoteAddr(ip);
            filter.doFilter(rejected, new MockHttpServletResponse(), chain);
        } catch (Exception e) {
            throw new IllegalStateException("rate limit filter raised an unexpected exception", e);
        }
    }

    private static void exerciseIngestPath(IngestMetrics metrics) {
        // Static, normalizer-supplied identifiers — exercises the production
        // wiring without feeding user input into the metric.
        metrics.recordInserted("provider1-json");
        metrics.recordUpdated("provider1-json");
        metrics.recordRejected("provider2-xml", "title");
    }

    private static void exerciseProviderFetchPath(ProviderFetchMetrics metrics) {
        metrics.record("provider1-json", Duration.ofMillis(12), true);
        metrics.record("provider2-xml", Duration.ofMillis(34), false);
    }

    // ─── Generators ──────────────────────────────────────────────────────────

    /**
     * Generates a 16–64 character alphanumeric search keyword. The 16-char
     * lower bound keeps the generated string longer than every structural tag
     * value the metric components emit ({@code success}, {@code failure},
     * {@code provider1-json}, etc.), so a clean implementation can never
     * legitimately contain {@code q} as a substring.
     */
    @Provide
    Arbitrary<String> randomQueries() {
        return Arbitraries.strings()
                .alpha().numeric()
                .ofMinLength(16)
                .ofMaxLength(64);
    }

    /**
     * Generates an IPv4 dotted-quad or an IPv6 colon-hex literal.
     */
    @Provide
    Arbitrary<String> randomIps() {
        Arbitrary<String> ipv4 = Combinators.combine(octet(), octet(), octet(), octet())
                .as((a, b, c, d) -> a + "." + b + "." + c + "." + d);
        Arbitrary<String> ipv6 = Arbitraries.integers().between(0, 0xFFFF)
                .map(i -> String.format(Locale.ROOT, "%x", i))
                .list().ofSize(8)
                .map(parts -> String.join(":", parts));
        return Arbitraries.oneOf(ipv4, ipv6);
    }

    private static Arbitrary<String> octet() {
        return Arbitraries.integers().between(1, 254).map(Object::toString);
    }

    // ─── In-memory ContentRepository ─────────────────────────────────────────

    /**
     * Minimal in-memory repository that returns a fixed canned content item
     * for every search. The fixed UUID and provider name keep the search path
     * fully deterministic — no random data leaks beyond the {@code q} the
     * iteration generated.
     */
    private static final class FakeRepository implements ContentRepository {

        private static final Content STUB = new Content(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "fake-provider",
                "ext-1",
                "Stub title",
                "Stub description",
                ContentType.TEXT,
                10L, 1L, 5, 1L, null,
                List.of(),
                Instant.parse("2024-06-01T00:00:00Z"),
                1.0, 1.0, 0.0,
                Instant.parse("2024-06-01T00:00:00Z"),
                Instant.parse("2024-06-01T00:00:00Z")
        );

        @Override
        public UpsertOutcome upsert(Content content) {
            throw new UnsupportedOperationException("not used in this property test");
        }

        @Override
        public SearchPage search(SearchCriteria criteria) {
            return new SearchPage(List.of(STUB), 1L);
        }

        @Override
        public SearchPage listTop(SortField sort, ContentType type, int limit) {
            return new SearchPage(List.of(STUB), 1L);
        }

        @Override
        public Optional<Content> findById(UUID id) {
            return Optional.empty();
        }
    }
}
