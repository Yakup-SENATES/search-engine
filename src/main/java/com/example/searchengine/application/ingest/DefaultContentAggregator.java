package com.example.searchengine.application.ingest;

import com.example.searchengine.domain.content.Content;
import com.example.searchengine.domain.content.ContentRepository;
import com.example.searchengine.domain.content.UpsertOutcome;
import com.example.searchengine.domain.provider.ContentProvider;
import com.example.searchengine.domain.provider.RawContent;
import com.example.searchengine.domain.scoring.ScoreBreakdown;
import com.example.searchengine.domain.scoring.ScoringEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Default implementation of {@link ContentAggregator} that orchestrates the
 * full sync pipeline: fetch → normalize → score → upsert.
 *
 * <p>Provider failures are isolated — one failing provider does not abort others
 * (REQ 11.3). Per-item failures (normalization rejection, DB errors) are logged
 * and skipped without aborting the batch (REQ 5.6).</p>
 *
 * <p>Cache eviction of the "search" region occurs after a successful run
 * (REQ 11.4, REQ 12.4).</p>
 */
@Component
public class DefaultContentAggregator implements ContentAggregator {

    private static final Logger log = LoggerFactory.getLogger(DefaultContentAggregator.class);

    private final List<ContentProvider> providers;
    private final Normalizer normalizer;
    private final ScoringEngine scoringEngine;
    private final ContentRepository contentRepository;
    private final Clock clock;

    public DefaultContentAggregator(List<ContentProvider> providers,
                                    Normalizer normalizer,
                                    ScoringEngine scoringEngine,
                                    ContentRepository contentRepository,
                                    Clock clock) {
        this.providers = providers;
        this.normalizer = normalizer;
        this.scoringEngine = scoringEngine;
        this.contentRepository = contentRepository;
        this.clock = clock;
    }

    @Override
    @CacheEvict(cacheNames = "search", allEntries = true)
    public void runSync() {
        Instant evaluationAt = clock.instant();

        for (ContentProvider provider : providers) {
            long fetchStart = System.nanoTime();
            List<RawContent> rawItems;

            try {
                rawItems = provider.fetch();
            } catch (Exception e) {
                long elapsedMs = (System.nanoTime() - fetchStart) / 1_000_000;
                log.error("Provider fetch failed provider={} elapsedMs={} cause={}",
                        provider.name(), elapsedMs, e.toString());
                continue; // REQ 11.3: isolate provider failure
            }

            long elapsedMs = (System.nanoTime() - fetchStart) / 1_000_000;
            log.info("Provider fetch completed provider={} items={} elapsedMs={}",
                    provider.name(), rawItems.size(), elapsedMs);

            for (RawContent raw : rawItems) {
                processItem(provider.name(), raw, evaluationAt);
            }
        }
    }

    private void processItem(String providerName, RawContent raw, Instant evaluationAt) {
        NormalizationResult result = normalizer.normalize(providerName, raw);

        if (result instanceof NormalizationResult.Rejected rejected) {
            log.warn("Normalization rejected provider={} externalId={} field={} reason={}",
                    providerName, raw.externalId(), rejected.field(), rejected.reason());
            return;
        }

        NormalizationResult.Accepted accepted = (NormalizationResult.Accepted) result;
        Content content = accepted.content();

        // Score the content
        ScoreBreakdown breakdown = scoringEngine.score(content, evaluationAt);

        // Create a new Content with the computed scores
        Content scored = new Content(
                content.id(),
                content.provider(),
                content.externalId(),
                content.title(),
                content.description(),
                content.type(),
                content.views(),
                content.likes(),
                content.readingTime(),
                content.reactions(),
                content.duration(),
                content.tags(),
                content.publishedAt(),
                breakdown.finalScore(),
                (breakdown.baseScore() * breakdown.typeMultiplier()) + breakdown.engagementScore(),
                content.relevanceScore(),
                content.createdAt(),
                content.updatedAt()
        );

        // Upsert with failure isolation (REQ 5.6)
        try {
            UpsertOutcome outcome = contentRepository.upsert(scored);
            log.info("Content upserted provider={} externalId={} outcome={}",
                    providerName, scored.externalId(), outcome.name().toLowerCase());
        } catch (Exception e) {
            log.error("Content upsert failed provider={} externalId={} cause={}",
                    providerName, scored.externalId(), e.toString());
        }
    }
}
