package com.example.searchengine.application.ingest;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.searchengine.domain.content.Content;
import com.example.searchengine.domain.content.ContentRepository;
import com.example.searchengine.domain.content.ContentType;
import com.example.searchengine.domain.content.UpsertOutcome;
import com.example.searchengine.domain.provider.ContentProvider;
import com.example.searchengine.domain.provider.RawContent;
import com.example.searchengine.domain.scoring.ScoreBreakdown;
import com.example.searchengine.domain.scoring.ScoringEngine;
import com.example.searchengine.infrastructure.admin.ProviderHealthRegistry;
import com.example.searchengine.infrastructure.metrics.IngestMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link DefaultContentAggregator} orchestration.
 *
 * <p>Validates Requirements 5.6, 5.7, 11.3, 11.4, 12.4, 16.3</p>
 */
@ExtendWith(MockitoExtension.class)
class DefaultContentAggregatorTest {

    private static final Instant FIXED_NOW = Instant.parse("2024-08-01T12:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);

    @Mock
    private Normalizer normalizer;

    @Mock
    private ScoringEngine scoringEngine;

    @Mock
    private ContentRepository contentRepository;

    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        // Attach a ListAppender to capture log output from DefaultContentAggregator
        Logger logger = (Logger) LoggerFactory.getLogger(DefaultContentAggregator.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        logger.addAppender(logAppender);
    }

    // ─── (a) Provider failure isolation ──────────────────────────────────────────

    @Test
    @DisplayName("One provider throwing does not abort others (REQ 11.3)")
    void providerFailureIsolation_otherProvidersStillProcessed() {
        // Arrange: two providers, first throws, second returns items
        ContentProvider failingProvider = mock(ContentProvider.class);
        when(failingProvider.name()).thenReturn("failing-provider");
        when(failingProvider.fetch()).thenThrow(new RuntimeException("Connection refused"));

        ContentProvider healthyProvider = mock(ContentProvider.class);
        when(healthyProvider.name()).thenReturn("healthy-provider");
        RawContent rawItem = validRawContent("item-1");
        when(healthyProvider.fetch()).thenReturn(List.of(rawItem));

        Content content = validContent("healthy-provider", "item-1");
        when(normalizer.normalize(eq("healthy-provider"), any(RawContent.class)))
                .thenReturn(new NormalizationResult.Accepted(content));
        when(scoringEngine.score(any(Content.class), any(Instant.class)))
                .thenReturn(new ScoreBreakdown(10.0, 1.5, 0.8, 5.0, 20.8));
        when(contentRepository.upsert(any(Content.class)))
                .thenReturn(UpsertOutcome.INSERTED);

        DefaultContentAggregator aggregator = new DefaultContentAggregator(
                List.of(failingProvider, healthyProvider),
                normalizer, scoringEngine, contentRepository, FIXED_CLOCK,
                new IngestMetrics(new SimpleMeterRegistry()),
                new ProviderHealthRegistry(FIXED_CLOCK)
        );

        // Act
        aggregator.runSync();

        // Assert: healthy provider's item was still processed
        verify(contentRepository).upsert(any(Content.class));
        verify(normalizer).normalize(eq("healthy-provider"), eq(rawItem));
    }

    // ─── (b) Repository exception on one item does not abort the batch ───────────

    @Test
    @DisplayName("Repository exception on one item does not abort the batch (REQ 5.6)")
    void repositoryExceptionIsolation_otherItemsStillProcessed() {
        // Arrange: one provider with two items, first item causes DB failure
        ContentProvider provider = mock(ContentProvider.class);
        when(provider.name()).thenReturn("test-provider");
        RawContent rawItem1 = validRawContent("item-1");
        RawContent rawItem2 = validRawContent("item-2");
        when(provider.fetch()).thenReturn(List.of(rawItem1, rawItem2));

        Content content1 = validContent("test-provider", "item-1");
        Content content2 = validContent("test-provider", "item-2");
        when(normalizer.normalize(eq("test-provider"), eq(rawItem1)))
                .thenReturn(new NormalizationResult.Accepted(content1));
        when(normalizer.normalize(eq("test-provider"), eq(rawItem2)))
                .thenReturn(new NormalizationResult.Accepted(content2));
        when(scoringEngine.score(any(Content.class), any(Instant.class)))
                .thenReturn(new ScoreBreakdown(10.0, 1.0, 0.5, 3.0, 13.5));

        // First upsert throws, second succeeds
        when(contentRepository.upsert(any(Content.class)))
                .thenThrow(new RuntimeException("DB connection lost"))
                .thenReturn(UpsertOutcome.INSERTED);

        DefaultContentAggregator aggregator = new DefaultContentAggregator(
                List.of(provider),
                normalizer, scoringEngine, contentRepository, FIXED_CLOCK,
                new IngestMetrics(new SimpleMeterRegistry()),
                new ProviderHealthRegistry(FIXED_CLOCK)
        );

        // Act
        aggregator.runSync();

        // Assert: upsert was called for both items (second was not aborted)
        verify(contentRepository, times(2)).upsert(any(Content.class));
    }

    // ─── (c) Cache eviction via @CacheEvict annotation ───────────────────────────

    @Test
    @DisplayName("runSync is annotated with @CacheEvict(cacheNames='search', allEntries=true) (REQ 11.4, 12.4)")
    void cacheEviction_annotationPresent() throws NoSuchMethodException {
        // Verify the @CacheEvict annotation is present on runSync()
        var method = DefaultContentAggregator.class.getMethod("runSync");
        var cacheEvict = method.getAnnotation(
                org.springframework.cache.annotation.CacheEvict.class);

        assertThat(cacheEvict).isNotNull();
        assertThat(cacheEvict.cacheNames()).contains("search");
        assertThat(cacheEvict.allEntries()).isTrue();
    }

    // ─── (d) Per-fetch info log includes elapsed milliseconds ────────────────────

    @Test
    @DisplayName("Per-fetch info log includes elapsed milliseconds (REQ 16.3)")
    void perFetchLog_includesElapsedMilliseconds() {
        // Arrange: one provider that returns successfully
        ContentProvider provider = mock(ContentProvider.class);
        when(provider.name()).thenReturn("timed-provider");
        when(provider.fetch()).thenReturn(List.of());

        DefaultContentAggregator aggregator = new DefaultContentAggregator(
                List.of(provider),
                normalizer, scoringEngine, contentRepository, FIXED_CLOCK,
                new IngestMetrics(new SimpleMeterRegistry()),
                new ProviderHealthRegistry(FIXED_CLOCK)
        );

        // Act
        aggregator.runSync();

        // Assert: an INFO log entry was emitted containing "elapsedMs="
        List<ILoggingEvent> infoLogs = logAppender.list.stream()
                .filter(e -> e.getLevel() == Level.INFO)
                .filter(e -> e.getFormattedMessage().contains("timed-provider"))
                .toList();

        assertThat(infoLogs).isNotEmpty();
        // The log message should contain elapsedMs with a numeric value
        String logMessage = infoLogs.get(0).getFormattedMessage();
        assertThat(logMessage).containsPattern("elapsedMs=\\d+");
    }

    @Test
    @DisplayName("Per-fetch error log on provider failure also includes elapsed milliseconds (REQ 16.3)")
    void perFetchErrorLog_includesElapsedMilliseconds() {
        // Arrange: one provider that throws
        ContentProvider provider = mock(ContentProvider.class);
        when(provider.name()).thenReturn("error-provider");
        when(provider.fetch()).thenThrow(new RuntimeException("Timeout"));

        DefaultContentAggregator aggregator = new DefaultContentAggregator(
                List.of(provider),
                normalizer, scoringEngine, contentRepository, FIXED_CLOCK,
                new IngestMetrics(new SimpleMeterRegistry()),
                new ProviderHealthRegistry(FIXED_CLOCK)
        );

        // Act
        aggregator.runSync();

        // Assert: an ERROR log entry was emitted containing "elapsedMs="
        List<ILoggingEvent> errorLogs = logAppender.list.stream()
                .filter(e -> e.getLevel() == Level.ERROR)
                .filter(e -> e.getFormattedMessage().contains("error-provider"))
                .toList();

        assertThat(errorLogs).isNotEmpty();
        String logMessage = errorLogs.get(0).getFormattedMessage();
        assertThat(logMessage).containsPattern("elapsedMs=\\d+");
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────────

    private RawContent validRawContent(String externalId) {
        return new RawContent(
                externalId,
                "Test Title " + externalId,
                "Test description",
                "video",
                5000L,
                200L,
                0,
                0L,
                "PT10M",
                List.of("test"),
                Instant.parse("2024-07-15T10:00:00Z")
        );
    }

    private Content validContent(String provider, String externalId) {
        return new Content(
                UUID.randomUUID(),
                provider,
                externalId,
                "Test Title " + externalId,
                "Test description",
                ContentType.VIDEO,
                5000L,
                200L,
                0,
                0L,
                "PT10M",
                List.of("test"),
                Instant.parse("2024-07-15T10:00:00Z"),
                0.0,
                0.0,
                0.0,
                null,
                null
        );
    }
}
