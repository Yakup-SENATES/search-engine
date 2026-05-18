package com.example.searchengine.web.api;

import com.example.searchengine.application.ingest.ProviderHealthService;
import com.example.searchengine.application.ingest.ProviderHealthService.ProviderHealthInfo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit test for {@link AdminProviderController}.
 *
 * <p>Uses standalone MockMvc setup to verify the JSON envelope shape and that
 * the controller delegates to {@link ProviderHealthService#getAll()} (REQ 2.1–2.5).</p>
 */
class AdminProviderControllerTest {

    private MockMvc mockMvc;
    private ProviderHealthService providerHealthService;

    @BeforeEach
    void setUp() {
        providerHealthService = mock(ProviderHealthService.class);
        AdminProviderController controller = new AdminProviderController(providerHealthService);

        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter(objectMapper);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(converter)
                .build();
    }

    @Test
    @DisplayName("GET /api/v1/admin/providers returns 200 with providers envelope when registry is empty")
    void emptyRegistry_returnsEmptyProvidersList() throws Exception {
        when(providerHealthService.getAll()).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/api/v1/admin/providers"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.providers").isArray())
                .andExpect(jsonPath("$.providers").isEmpty());

        verify(providerHealthService).getAll();
    }

    @Test
    @DisplayName("GET /api/v1/admin/providers returns provider snapshots with all seven fields")
    void populatedRegistry_returnsAllSevenFields() throws Exception {
        Instant syncTime = Instant.parse("2024-06-15T10:30:00Z");

        List<ProviderHealthInfo> infos = List.of(
                new ProviderHealthInfo(
                        "json-provider", syncTime, "success", 42, 5L, 1L, null),
                new ProviderHealthInfo(
                        "xml-provider", syncTime, "failure", 0, 3L, 2L, "Connection timed out")
        );

        when(providerHealthService.getAll()).thenReturn(infos);

        mockMvc.perform(get("/api/v1/admin/providers"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.providers").isArray())
                .andExpect(jsonPath("$.providers.length()").value(2))
                // First provider — successful
                .andExpect(jsonPath("$.providers[0].name").value("json-provider"))
                .andExpect(jsonPath("$.providers[0].lastSyncAt").value("2024-06-15T10:30:00Z"))
                .andExpect(jsonPath("$.providers[0].lastSyncOutcome").value("success"))
                .andExpect(jsonPath("$.providers[0].lastFetchedItems").value(42))
                .andExpect(jsonPath("$.providers[0].totalSuccesses").value(5))
                .andExpect(jsonPath("$.providers[0].totalFailures").value(1))
                .andExpect(jsonPath("$.providers[0].lastErrorMessage").doesNotExist())
                // Second provider — failed
                .andExpect(jsonPath("$.providers[1].name").value("xml-provider"))
                .andExpect(jsonPath("$.providers[1].lastSyncAt").value("2024-06-15T10:30:00Z"))
                .andExpect(jsonPath("$.providers[1].lastSyncOutcome").value("failure"))
                .andExpect(jsonPath("$.providers[1].lastFetchedItems").value(0))
                .andExpect(jsonPath("$.providers[1].totalSuccesses").value(3))
                .andExpect(jsonPath("$.providers[1].totalFailures").value(2))
                .andExpect(jsonPath("$.providers[1].lastErrorMessage").value("Connection timed out"));

        verify(providerHealthService).getAll();
    }

    @Test
    @DisplayName("GET /api/v1/admin/providers returns null fields for never-synced provider")
    void neverSyncedProvider_returnsNullFields() throws Exception {
        List<ProviderHealthInfo> infos = List.of(
                new ProviderHealthInfo("new-provider", null, null, 0, 0L, 0L, null)
        );

        when(providerHealthService.getAll()).thenReturn(infos);

        mockMvc.perform(get("/api/v1/admin/providers"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.providers[0].name").value("new-provider"))
                .andExpect(jsonPath("$.providers[0].lastSyncAt").doesNotExist())
                .andExpect(jsonPath("$.providers[0].lastSyncOutcome").doesNotExist())
                .andExpect(jsonPath("$.providers[0].lastFetchedItems").value(0))
                .andExpect(jsonPath("$.providers[0].totalSuccesses").value(0))
                .andExpect(jsonPath("$.providers[0].totalFailures").value(0))
                .andExpect(jsonPath("$.providers[0].lastErrorMessage").doesNotExist());
    }
}
