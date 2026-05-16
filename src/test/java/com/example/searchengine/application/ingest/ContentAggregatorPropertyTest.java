package com.example.searchengine.application.ingest;

import com.example.searchengine.domain.content.Content;
import com.example.searchengine.domain.content.ContentRepository;
import com.example.searchengine.domain.content.UpsertOutcome;
import com.example.searchengine.domain.provider.ContentProvider;
import com.example.searchengine.domain.provider.RawContent;
import com.example.searchengine.domain.scoring.ScoreBreakdown;
import com.example.searchengine.domain.scoring.ScoringEngine;
import net.jqwik.api.*;
import net.jqwik.api.lifecycle.BeforeProperty;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for per-item and per-provider failure isolation in the
 * {@link DefaultContentAggregator}.
 *
 * <p><b>Validates: Requirements 2.4, 2.5, 2.6, 3.6, 5.6, 11.3</b></p>
 *
 * <p>For any sync batch containing a random mix of valid items, parse-failing items,
 * DB-failing items, and a random subset of providers throwing during {@code fetch()},
 * every valid item from every non-throwing provider is normalized, scored, and
 * successfully upserted, and no exception escapes the {@code Content_Aggregator}.</p>
 */
class ContentAggregatorPropertyTest {

    private static final Instant FIXED_NOW = Instant.parse("2024-06-15T12:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);

    /**
     * Describes the behavior of a single item in the test matrix.
     */
    enum ItemBehavior {
        /** Valid item that should be normalized, scored, and upserted successfully. */
        VALID,
        /** Item with invalid data that causes normalization to reject it. */
        PARSE_FAIL,
        /** Valid item but the repository throws on upsert. */
        DB_FAIL
    }

    /**
     * Describes a single provider in the test matrix.
     */
    record ProviderSpec(String name, boolean throwsOnFetch, List<ItemBehavior> items) {}

    /**
     * Tracks which items were successfully upserted.
     */
    private final List<String> upsertedExternalIds = new CopyOnWriteArrayList<>();

    @BeforeProperty
    void resetState() {
        upsertedExternalIds.clear();
    }

    @Property(tries = 100)
    @Label("Feature: search-engine-service, Property 2: Per-item and per-provider failure isolation")
    void failureIsolation(@ForAll("providerMatrix") List<ProviderSpec> providerSpecs) {
        upsertedExternalIds.clear();

        // Track which externalIds should fail on DB upsert
        Set<String> dbFailIds = new HashSet<>();

        // Build stub providers
        List<ContentProvider> providers = new ArrayList<>();
        for (ProviderSpec spec : providerSpecs) {
            providers.add(new StubProvider(spec));
        }

        // Build a normalizer that uses the real DefaultNormalizer logic
        Normalizer normalizer = new DefaultNormalizer();

        // Build a scoring engine that returns a fixed breakdown
        ScoringEngine scoringEngine = (content, evaluationAt) ->
                new ScoreBreakdown(10.0, 1.0, 2.0, 3.0, 15.0);

        // Build a content repository that tracks upserts and throws for DB_FAIL items
        // We identify DB_FAIL items by their externalId containing "dbfail"
        for (ProviderSpec spec : providerSpecs) {
            if (spec.throwsOnFetch()) continue;
            int itemIndex = 0;
            for (ItemBehavior behavior : spec.items()) {
                if (behavior == ItemBehavior.DB_FAIL) {
                    dbFailIds.add(buildExternalId(spec.name(), itemIndex));
                }
                itemIndex++;
            }
        }

        ContentRepository repository = new ContentRepository() {
            @Override
            public UpsertOutcome upsert(Content content) {
                if (dbFailIds.contains(content.externalId())) {
                    throw new RuntimeException("Simulated DB failure for " + content.externalId());
                }
                upsertedExternalIds.add(content.externalId());
                return UpsertOutcome.INSERTED;
            }

            @Override
            public com.example.searchengine.domain.content.SearchPage search(
                    com.example.searchengine.domain.content.SearchCriteria criteria) {
                throw new UnsupportedOperationException("Not used in this test");
            }

            @Override
            public com.example.searchengine.domain.content.SearchPage listTop(
                    com.example.searchengine.domain.content.SortField sort,
                    com.example.searchengine.domain.content.ContentType type,
                    int limit) {
                throw new UnsupportedOperationException("Not used in this test");
            }

            @Override
            public Optional<Content> findById(UUID id) {
                throw new UnsupportedOperationException("Not used in this test");
            }
        };

        // Build the aggregator
        DefaultContentAggregator aggregator = new DefaultContentAggregator(
                providers, normalizer, scoringEngine, repository, FIXED_CLOCK
        );

        // Act: runSync should never throw
        aggregator.runSync();

        // Assert: every valid item from every non-throwing provider was upserted
        Set<String> expectedUpserted = new HashSet<>();
        for (ProviderSpec spec : providerSpecs) {
            if (spec.throwsOnFetch()) continue;
            int itemIndex = 0;
            for (ItemBehavior behavior : spec.items()) {
                if (behavior == ItemBehavior.VALID) {
                    expectedUpserted.add(buildExternalId(spec.name(), itemIndex));
                }
                itemIndex++;
            }
        }

        assertThat(new HashSet<>(upsertedExternalIds)).isEqualTo(expectedUpserted);
    }

    @Provide
    Arbitrary<List<ProviderSpec>> providerMatrix() {
        // Generate 1-5 providers with unique names, each with 0-8 items
        return Arbitraries.integers().between(1, 5).flatMap(numProviders -> {
            // Build a fixed-size list of provider specs with unique indices
            Arbitrary<List<ProviderSpec>> result = Arbitraries.just(new ArrayList<ProviderSpec>());
            for (int i = 0; i < numProviders; i++) {
                final int providerIndex = i;
                result = result.flatMap(list -> Combinators.combine(
                        Arbitraries.of(true, false),
                        Arbitraries.of(ItemBehavior.values()).list().ofMinSize(0).ofMaxSize(8)
                ).as((throwsOnFetch, items) -> {
                    List<ProviderSpec> newList = new ArrayList<>(list);
                    newList.add(new ProviderSpec("provider-" + providerIndex, throwsOnFetch, items));
                    return newList;
                }));
            }
            return result;
        });
    }

    /**
     * Builds a deterministic externalId for a given provider and item index.
     */
    private static String buildExternalId(String providerName, int itemIndex) {
        return providerName + "-item-" + itemIndex;
    }

    /**
     * Builds a RawContent for a given behavior.
     */
    private static RawContent buildRawContent(String providerName, int itemIndex, ItemBehavior behavior) {
        String externalId = buildExternalId(providerName, itemIndex);

        return switch (behavior) {
            case VALID -> new RawContent(
                    externalId,
                    "Title for " + externalId,
                    "Description for " + externalId,
                    "video",
                    1000L,
                    100L,
                    0,
                    0L,
                    "PT5M",
                    List.of("tag1"),
                    Instant.parse("2024-06-10T10:00:00Z")
            );
            case PARSE_FAIL -> new RawContent(
                    externalId,
                    "",  // blank title causes normalization rejection
                    null,
                    "video",
                    1000L,
                    100L,
                    0,
                    0L,
                    null,
                    List.of(),
                    Instant.parse("2024-06-10T10:00:00Z")
            );
            case DB_FAIL -> new RawContent(
                    externalId,
                    "Title for " + externalId,
                    "Description for " + externalId,
                    "text",
                    0L,
                    0L,
                    10,
                    50L,
                    null,
                    List.of("tag2"),
                    Instant.parse("2024-06-01T08:00:00Z")
            );
        };
    }

    /**
     * Stub ContentProvider that either throws on fetch or returns items
     * based on the ProviderSpec.
     */
    private static class StubProvider implements ContentProvider {
        private final ProviderSpec spec;

        StubProvider(ProviderSpec spec) {
            this.spec = spec;
        }

        @Override
        public String name() {
            return spec.name();
        }

        @Override
        public List<RawContent> fetch() {
            if (spec.throwsOnFetch()) {
                throw new RuntimeException("Simulated provider fetch failure for " + spec.name());
            }
            List<RawContent> items = new ArrayList<>();
            for (int i = 0; i < spec.items().size(); i++) {
                items.add(buildRawContent(spec.name(), i, spec.items().get(i)));
            }
            return items;
        }
    }
}
