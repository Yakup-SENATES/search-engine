package com.example.searchengine.infrastructure.admin;

import com.example.searchengine.application.ingest.ProviderHealthService;

import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Default implementation of {@link ProviderHealthService} backed by the
 * in-memory {@link ProviderHealthRegistry}.
 */
@Service
public class DefaultProviderHealthService implements ProviderHealthService {

    private final ProviderHealthRegistry registry;

    public DefaultProviderHealthService(ProviderHealthRegistry registry) {
        this.registry = registry;
    }

    @Override
    public List<ProviderHealthInfo> getAll() {
        return registry.getSnapshots().values().stream()
                .map(snapshot -> new ProviderHealthInfo(
                        snapshot.name(),
                        snapshot.lastSyncAt(),
                        snapshot.lastSyncOutcome(),
                        snapshot.lastFetchedItems(),
                        snapshot.totalSuccesses(),
                        snapshot.totalFailures(),
                        snapshot.lastErrorMessage()
                ))
                .toList();
    }
}
