package com.example.searchengine.infrastructure.provider.xmlprovider;

import com.example.searchengine.domain.provider.RawContent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link XmlProviderAdapter}.
 *
 * <p>Validates:
 * <ul>
 *   <li>(a) Happy path: valid XML feed mapped field-for-field to RawContent (REQ 3.2)</li>
 *   <li>(b) One item missing required field is dropped, siblings persist (REQ 3.5)</li>
 *   <li>(c) Malformed/empty response returns empty list (REQ 3.6)</li>
 *   <li>(d) Client throws exception returns empty list (REQ 3.6)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class XmlProviderAdapterTest {

    @Mock
    private XmlProviderClient client;

    private XmlProviderAdapter adapter;

    @BeforeEach
    void setUp() {
        XmlContentMapper mapper = new XmlContentMapper();
        adapter = new XmlProviderAdapter(client, mapper);
    }

    @Test
    @DisplayName("name() returns 'provider2-xml'")
    void name_returnsProviderName() {
        assertThat(adapter.name()).isEqualTo("provider2-xml");
    }

    @Test
    @DisplayName("(a) Happy path: valid XML feed is mapped field-for-field to RawContent")
    void fetch_happyPath_mapsFieldsCorrectly() {
        // Given
        XmlStatsDto stats = new XmlStatsDto();
        stats.setViews(8000L);
        stats.setLikes(500L);
        stats.setReadingTime(12);
        stats.setReactions(45L);

        XmlItemDto item = new XmlItemDto();
        item.setId("art-001");
        item.setHeadline("Understanding Clean Architecture");
        item.setType("article");
        item.setStats(stats);
        item.setPublicationDate("2024-01-10T08:00:00Z");
        item.setCategories(List.of("architecture", "java"));

        XmlFeedDto feed = new XmlFeedDto();
        feed.setItems(List.of(item));

        when(client.fetchFeed()).thenReturn(Optional.of(feed));

        // When
        List<RawContent> results = adapter.fetch();

        // Then
        assertThat(results).hasSize(1);
        RawContent raw = results.get(0);
        assertThat(raw.externalId()).isEqualTo("art-001");
        assertThat(raw.title()).isEqualTo("Understanding Clean Architecture");
        assertThat(raw.type()).isEqualTo("article");
        assertThat(raw.views()).isEqualTo(8000L);
        assertThat(raw.likes()).isEqualTo(500L);
        assertThat(raw.readingTime()).isEqualTo(12);
        assertThat(raw.reactions()).isEqualTo(45L);
        assertThat(raw.publishedAt()).isEqualTo(Instant.parse("2024-01-10T08:00:00Z"));
        assertThat(raw.tags()).containsExactly("architecture", "java");
        assertThat(raw.duration()).isNull();
        assertThat(raw.description()).isNull();
    }

    @Test
    @DisplayName("(a) Happy path: video type item is mapped correctly")
    void fetch_videoType_mapsCorrectly() {
        // Given
        XmlStatsDto stats = new XmlStatsDto();
        stats.setViews(20000L);
        stats.setLikes(1500L);
        stats.setReadingTime(0);
        stats.setReactions(0L);

        XmlItemDto item = new XmlItemDto();
        item.setId("vid-001");
        item.setHeadline("Spring Boot Deep Dive");
        item.setType("video");
        item.setStats(stats);
        item.setPublicationDate("2024-03-15T14:30:00Z");
        item.setCategories(List.of("spring", "tutorial"));

        XmlFeedDto feed = new XmlFeedDto();
        feed.setItems(List.of(item));

        when(client.fetchFeed()).thenReturn(Optional.of(feed));

        // When
        List<RawContent> results = adapter.fetch();

        // Then
        assertThat(results).hasSize(1);
        RawContent raw = results.get(0);
        assertThat(raw.externalId()).isEqualTo("vid-001");
        assertThat(raw.type()).isEqualTo("video");
        assertThat(raw.views()).isEqualTo(20000L);
        assertThat(raw.likes()).isEqualTo(1500L);
    }

    @Test
    @DisplayName("(a) Absent optional stats defaults to zero (REQ 3.5)")
    void fetch_absentStats_defaultsToZero() {
        // Given
        XmlItemDto item = new XmlItemDto();
        item.setId("art-002");
        item.setHeadline("No Stats Article");
        item.setType("article");
        item.setStats(null); // stats absent
        item.setPublicationDate("2024-02-20T09:00:00Z");
        item.setCategories(null); // categories absent

        XmlFeedDto feed = new XmlFeedDto();
        feed.setItems(List.of(item));

        when(client.fetchFeed()).thenReturn(Optional.of(feed));

        // When
        List<RawContent> results = adapter.fetch();

        // Then
        assertThat(results).hasSize(1);
        RawContent raw = results.get(0);
        assertThat(raw.views()).isZero();
        assertThat(raw.likes()).isZero();
        assertThat(raw.readingTime()).isZero();
        assertThat(raw.reactions()).isZero();
        assertThat(raw.tags()).isEmpty();
    }

    @Test
    @DisplayName("(b) One item missing required field 'headline' is dropped, valid sibling persists")
    void fetch_itemMissingHeadline_droppedWhileSiblingPersists() {
        // Given
        XmlStatsDto stats = new XmlStatsDto();
        stats.setViews(1000L);
        stats.setLikes(50L);

        // Invalid item: missing headline
        XmlItemDto invalidItem = new XmlItemDto();
        invalidItem.setId("art-003");
        invalidItem.setHeadline(null);
        invalidItem.setType("article");
        invalidItem.setStats(stats);
        invalidItem.setPublicationDate("2024-01-05T10:00:00Z");

        // Valid item
        XmlItemDto validItem = new XmlItemDto();
        validItem.setId("art-004");
        validItem.setHeadline("Valid Article");
        validItem.setType("article");
        validItem.setStats(stats);
        validItem.setPublicationDate("2024-01-06T10:00:00Z");
        validItem.setCategories(List.of("tech"));

        XmlFeedDto feed = new XmlFeedDto();
        feed.setItems(List.of(invalidItem, validItem));

        when(client.fetchFeed()).thenReturn(Optional.of(feed));

        // When
        List<RawContent> results = adapter.fetch();

        // Then
        assertThat(results).hasSize(1);
        assertThat(results.get(0).externalId()).isEqualTo("art-004");
        assertThat(results.get(0).title()).isEqualTo("Valid Article");
    }

    @Test
    @DisplayName("(b) One item missing required field 'id' is dropped, valid sibling persists")
    void fetch_itemMissingId_droppedWhileSiblingPersists() {
        // Given
        XmlStatsDto stats = new XmlStatsDto();
        stats.setViews(500L);

        XmlItemDto invalidItem = new XmlItemDto();
        invalidItem.setId(null);
        invalidItem.setHeadline("No ID");
        invalidItem.setType("article");
        invalidItem.setStats(stats);
        invalidItem.setPublicationDate("2024-01-07T10:00:00Z");

        XmlItemDto validItem = new XmlItemDto();
        validItem.setId("art-005");
        validItem.setHeadline("Has ID");
        validItem.setType("video");
        validItem.setStats(stats);
        validItem.setPublicationDate("2024-01-08T10:00:00Z");

        XmlFeedDto feed = new XmlFeedDto();
        feed.setItems(List.of(invalidItem, validItem));

        when(client.fetchFeed()).thenReturn(Optional.of(feed));

        // When
        List<RawContent> results = adapter.fetch();

        // Then
        assertThat(results).hasSize(1);
        assertThat(results.get(0).externalId()).isEqualTo("art-005");
    }

    @Test
    @DisplayName("(b) One item with unrecognized type is dropped, valid sibling persists")
    void fetch_itemUnrecognizedType_droppedWhileSiblingPersists() {
        // Given
        XmlStatsDto stats = new XmlStatsDto();
        stats.setViews(300L);

        XmlItemDto invalidItem = new XmlItemDto();
        invalidItem.setId("art-006");
        invalidItem.setHeadline("Unknown Type");
        invalidItem.setType("podcast"); // unrecognized type
        invalidItem.setStats(stats);
        invalidItem.setPublicationDate("2024-02-01T10:00:00Z");

        XmlItemDto validItem = new XmlItemDto();
        validItem.setId("art-007");
        validItem.setHeadline("Valid Type");
        validItem.setType("article");
        validItem.setStats(stats);
        validItem.setPublicationDate("2024-02-02T10:00:00Z");

        XmlFeedDto feed = new XmlFeedDto();
        feed.setItems(List.of(invalidItem, validItem));

        when(client.fetchFeed()).thenReturn(Optional.of(feed));

        // When
        List<RawContent> results = adapter.fetch();

        // Then
        assertThat(results).hasSize(1);
        assertThat(results.get(0).externalId()).isEqualTo("art-007");
    }

    @Test
    @DisplayName("(b) One item missing publication_date is dropped, valid sibling persists")
    void fetch_itemMissingPublicationDate_droppedWhileSiblingPersists() {
        // Given
        XmlStatsDto stats = new XmlStatsDto();
        stats.setViews(700L);

        XmlItemDto invalidItem = new XmlItemDto();
        invalidItem.setId("art-008");
        invalidItem.setHeadline("No Date");
        invalidItem.setType("article");
        invalidItem.setStats(stats);
        invalidItem.setPublicationDate(null);

        XmlItemDto validItem = new XmlItemDto();
        validItem.setId("art-009");
        validItem.setHeadline("Has Date");
        validItem.setType("article");
        validItem.setStats(stats);
        validItem.setPublicationDate("2024-03-01T10:00:00Z");

        XmlFeedDto feed = new XmlFeedDto();
        feed.setItems(List.of(invalidItem, validItem));

        when(client.fetchFeed()).thenReturn(Optional.of(feed));

        // When
        List<RawContent> results = adapter.fetch();

        // Then
        assertThat(results).hasSize(1);
        assertThat(results.get(0).externalId()).isEqualTo("art-009");
    }

    @Test
    @DisplayName("(c) Client returns empty Optional (malformed XML) returns empty list")
    void fetch_emptyOptional_returnsEmptyList() {
        // Given
        when(client.fetchFeed()).thenReturn(Optional.empty());

        // When
        List<RawContent> results = adapter.fetch();

        // Then
        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("(c) Feed with null items list returns empty list")
    void fetch_nullItemsList_returnsEmptyList() {
        // Given
        XmlFeedDto feed = new XmlFeedDto();
        feed.setItems(null);
        when(client.fetchFeed()).thenReturn(Optional.of(feed));

        // When
        List<RawContent> results = adapter.fetch();

        // Then
        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("(c) Feed with empty items list returns empty list")
    void fetch_emptyItemsList_returnsEmptyList() {
        // Given
        XmlFeedDto feed = new XmlFeedDto();
        feed.setItems(List.of());
        when(client.fetchFeed()).thenReturn(Optional.of(feed));

        // When
        List<RawContent> results = adapter.fetch();

        // Then
        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("(d) Client throws RuntimeException returns empty list")
    void fetch_clientThrowsException_returnsEmptyList() {
        // Given
        when(client.fetchFeed()).thenThrow(new RuntimeException("Connection timeout"));

        // When
        List<RawContent> results = adapter.fetch();

        // Then
        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("(d) Client throws RestClientException returns empty list")
    void fetch_clientThrowsRestClientException_returnsEmptyList() {
        // Given
        when(client.fetchFeed()).thenThrow(
                new org.springframework.web.client.RestClientException("HTTP 503 Service Unavailable"));

        // When
        List<RawContent> results = adapter.fetch();

        // Then
        assertThat(results).isEmpty();
    }
}
