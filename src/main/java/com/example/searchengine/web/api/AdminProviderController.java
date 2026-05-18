package com.example.searchengine.web.api;

import com.example.searchengine.application.ingest.ProviderHealthService;
import com.example.searchengine.application.ingest.ProviderHealthService.ProviderHealthInfo;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Admin REST controller exposing provider health state.
 *
 * <p>Returns the live state of every registered provider so operators can
 * diagnose ingest problems without reading log files (REQ 2.1–2.5).</p>
 *
 * <p>The endpoint lives under {@code /api/v1/admin/providers} and is protected
 * by the {@code AdminAuthFilter}.</p>
 */
@RestController
@RequestMapping("/api/v1/admin/providers")
public class AdminProviderController {

    private final ProviderHealthService providerHealthService;

    public AdminProviderController(ProviderHealthService providerHealthService) {
        this.providerHealthService = providerHealthService;
    }

    /**
     * Returns the health snapshot of every registered provider.
     *
     * @return HTTP 200 with JSON envelope {@code {"providers": [...]}}
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, List<ProviderHealthDto>> getProviders() {
        List<ProviderHealthDto> providers = providerHealthService.getAll().stream()
                .map(this::toDto)
                .toList();
        return Map.of("providers", providers);
    }

    private ProviderHealthDto toDto(ProviderHealthInfo info) {
        return new ProviderHealthDto(
                info.name(),
                info.lastSyncAt(),
                info.lastSyncOutcome(),
                info.lastFetchedItems(),
                info.totalSuccesses(),
                info.totalFailures(),
                info.lastErrorMessage()
        );
    }
}
