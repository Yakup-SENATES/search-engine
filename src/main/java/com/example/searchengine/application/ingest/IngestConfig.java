package com.example.searchengine.application.ingest;

import com.example.searchengine.domain.scoring.DefaultScoringEngine;
import com.example.searchengine.domain.scoring.ScoringEngine;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the application-layer ingest beans.
 *
 * <p>The {@link ScoringEngine} lives in the pure-Java domain layer and must not
 * depend on Spring (REQ 6.1, REQ 22.2 — enforced by ArchUnit). It is therefore
 * registered as a Spring bean from this {@code @Configuration} so the
 * {@link DefaultContentAggregator} can have it injected.</p>
 */
@Configuration
public class IngestConfig {

    @Bean
    public ScoringEngine scoringEngine() {
        return new DefaultScoringEngine();
    }
}
