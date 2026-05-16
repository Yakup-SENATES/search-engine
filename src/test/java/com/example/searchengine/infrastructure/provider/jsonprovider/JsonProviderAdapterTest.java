package com.example.searchengine.infrastructure.provider.jsonprovider;

import com.example.searchengine.domain.provider.RawContent;
import com.example.searchengine.infrastructure.config.ProviderProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClientException;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link JsonProviderAdapter}.
 *
 * <p>Validates:
 * <ul>
 *   <li>(a) Happy path: valid payload mapped field-for-field to RawContent (REQ 2.4)</li>
 *   <li>(b) One item missing required field is dropped, siblings persist (REQ 2.5)</li>
 *   <li>(c) Malformed/null response returns empty list (REQ 2.6)</li>
 *   <li>(d) Client throws RestClientException returns empty list (REQ 2.4)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class JsonProviderAdapterTest {

    @Mock
    private JsonProviderClient client;

    private JsonProviderAdapter adapter;

    @BeforeEach
    void setUp() {
        ProviderProperties properties = new ProviderProperties();
        ProviderProperties.ProviderEntry jsonEntry = new ProviderProperties.ProviderEntry();
        jsonEntry.setBaseUrl("http://localhost:8081/api/content");
        properties.setJson(jsonEntry);

        // XML entry needed to avoid NPE in ProviderProperties if validated
        ProviderProperties.ProviderEntry xmlEntry = new ProviderProperties.ProviderEntry();
        xmlEntry.setBaseUrl("http://localhost:8082/feed");
        properties.setXml(xmlEntry);

        adapter = new JsonProviderAdapter(client, properties);
    }

    @Test
    @DisplayName("name() returns 'provider1-json'")
    void name_returnsProviderName() {
        assertThat(adapter.name()).isEqualTo("provider1-json");
    }

    @Test
    @DisplayName("(a) Happy path: valid payload is mapped field-for-field to RawContent")
    void fetch_happyPath_mapsFieldsCorrectly() {
        // Given
        Instant publishedAt = Instant.parse("2024-01-15T10:00:00Z");
        JsonMetrics metrics = new JsonMetrics(15000L, 1200L, "PT10M");
        JsonContentDto dto = new JsonContentDto(
                "vid-001", "Java Spring Tutorial", "video",
                metrics, publishedAt, List.of("java", "spring")
        );
        JsonProviderResponse response = new JsonProviderResponse(List.of(dto));
        when(client.fetch()).thenReturn(response);

        // When
        List<RawContent> results = adapter.fetch();

        // Then
        assertThat(results).hasSize(1);
        RawContent raw = results.get(0);
        assertThat(raw.externalId()).isEqualTo("vid-001");
        assertThat(raw.title()).isEqualTo("Java Spring Tutorial");
        assertThat(raw.type()).isEqualTo("video");
        assertThat(raw.views()).isEqualTo(15000L);
        assertThat(raw.likes()).isEqualTo(1200L);
        assertThat(raw.duration()).isEqualTo("PT10M");
        assertThat(raw.publishedAt()).isEqualTo(publishedAt);
        assertThat(raw.tags()).containsExactly("java", "spring");
        assertThat(raw.readingTime()).isZero();
        assertThat(raw.reactions()).isZero();
        assertThat(raw.description()).isNull();
    }

    @Test
    @DisplayName("(b) One item missing required field 'title' is dropped, valid sibling persists")
    void fetch_itemMissingTitle_droppedWhileSiblingPersists() {
        // Given
        Instant publishedAt = Instant.parse("2024-02-01T12:00:00Z");
        JsonMetrics metrics = new JsonMetrics(5000L, 300L, "PT5M");

        // Item with missing title (should be dropped)
        JsonContentDto invalidItem = new JsonContentDto(
                "vid-002", null, "video",
                metrics, publishedAt, List.of("tech")
        );

        // Valid item (should persist)
        JsonContentDto validItem = new JsonContentDto(
                "vid-003", "Valid Title", "video",
                metrics, publishedAt, List.of("coding")
        );

        JsonProviderResponse response = new JsonProviderResponse(List.of(invalidItem, validItem));
        when(client.fetch()).thenReturn(response);

        // When
        List<RawContent> results = adapter.fetch();

        // Then
        assertThat(results).hasSize(1);
        assertThat(results.get(0).externalId()).isEqualTo("vid-003");
        assertThat(results.get(0).title()).isEqualTo("Valid Title");
    }

    @Test
    @DisplayName("(b) One item missing required field 'metrics' is dropped, valid sibling persists")
    void fetch_itemMissingMetrics_droppedWhileSiblingPersists() {
        // Given
        Instant publishedAt = Instant.parse("2024-02-01T12:00:00Z");
        JsonMetrics metrics = new JsonMetrics(5000L, 300L, "PT5M");

        // Item with null metrics (should be dropped)
        JsonContentDto invalidItem = new JsonContentDto(
                "vid-004", "No Metrics", "video",
                null, publishedAt, List.of()
        );

        // Valid item
        JsonContentDto validItem = new JsonContentDto(
                "vid-005", "Has Metrics", "video",
                metrics, publishedAt, List.of()
        );

        JsonProviderResponse response = new JsonProviderResponse(List.of(invalidItem, validItem));
        when(client.fetch()).thenReturn(response);

        // When
        List<RawContent> results = adapter.fetch();

        // Then
        assertThat(results).hasSize(1);
        assertThat(results.get(0).externalId()).isEqualTo("vid-005");
    }

    @Test
    @DisplayName("(b) One item missing required field 'id' is dropped, valid sibling persists")
    void fetch_itemMissingId_droppedWhileSiblingPersists() {
        // Given
        Instant publishedAt = Instant.parse("2024-03-01T08:00:00Z");
        JsonMetrics metrics = new JsonMetrics(1000L, 100L, "PT3M");

        JsonContentDto invalidItem = new JsonContentDto(
                null, "No ID Item", "video",
                metrics, publishedAt, List.of()
        );
        JsonContentDto validItem = new JsonContentDto(
                "vid-006", "Has ID", "video",
                metrics, publishedAt, List.of("valid")
        );

        JsonProviderResponse response = new JsonProviderResponse(List.of(invalidItem, validItem));
        when(client.fetch()).thenReturn(response);

        // When
        List<RawContent> results = adapter.fetch();

        // Then
        assertThat(results).hasSize(1);
        assertThat(results.get(0).externalId()).isEqualTo("vid-006");
    }

    @Test
    @DisplayName("(c) Null response returns empty list")
    void fetch_nullResponse_returnsEmptyList() {
        // Given
        when(client.fetch()).thenReturn(null);

        // When
        List<RawContent> results = adapter.fetch();

        // Then
        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("(c) Response with null contents list returns empty list")
    void fetch_nullContentsList_returnsEmptyList() {
        // Given
        JsonProviderResponse response = new JsonProviderResponse(null);
        when(client.fetch()).thenReturn(response);

        // When
        List<RawContent> results = adapter.fetch();

        // Then
        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("(d) Client throws RestClientException returns empty list")
    void fetch_restClientException_returnsEmptyList() {
        // Given
        when(client.fetch()).thenThrow(new RestClientException("Connection refused"));

        // When
        List<RawContent> results = adapter.fetch();

        // Then
        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("(d) Client throws RuntimeException returns empty list")
    void fetch_runtimeException_returnsEmptyList() {
        // Given
        when(client.fetch()).thenThrow(new RuntimeException("Unexpected error"));

        // When
        List<RawContent> results = adapter.fetch();

        // Then
        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("(b) Item missing 'published_at' is dropped, valid sibling persists")
    void fetch_itemMissingPublishedAt_droppedWhileSiblingPersists() {
        // Given
        Instant publishedAt = Instant.parse("2024-04-01T10:00:00Z");
        JsonMetrics metrics = new JsonMetrics(2000L, 200L, "PT7M");

        JsonContentDto invalidItem = new JsonContentDto(
                "vid-007", "No Date", "video",
                metrics, null, List.of()
        );
        JsonContentDto validItem = new JsonContentDto(
                "vid-008", "Has Date", "video",
                metrics, publishedAt, List.of()
        );

        JsonProviderResponse response = new JsonProviderResponse(List.of(invalidItem, validItem));
        when(client.fetch()).thenReturn(response);

        // When
        List<RawContent> results = adapter.fetch();

        // Then
        assertThat(results).hasSize(1);
        assertThat(results.get(0).externalId()).isEqualTo("vid-008");
    }
}
