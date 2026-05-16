package com.example.searchengine.infrastructure.persistence;

import com.example.searchengine.domain.content.Content;
import com.example.searchengine.domain.content.ContentType;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the entity↔domain mapping logic.
 *
 * <p>Tests round-trip conversion: Content → ContentEntity → Content and asserts
 * field-level equality including tags ordering and null description handling.</p>
 *
 * <p>Requirements: 4.1, 5.1, 5.2</p>
 */
class ContentEntityDomainMapperTest {

    // --- Helper: Content → ContentEntity ---

    private static ContentEntity toEntity(Content content) {
        ContentEntity entity = new ContentEntity();
        entity.setId(content.id());
        entity.setProvider(content.provider());
        entity.setExternalId(content.externalId());
        entity.setTitle(content.title());
        entity.setDescription(content.description());
        entity.setType(content.type());
        entity.setViews(content.views());
        entity.setLikes(content.likes());
        entity.setReadingTime(content.readingTime());
        entity.setReactions(content.reactions());
        entity.setDuration(content.duration());
        entity.setTags(content.tags().toArray(new String[0]));
        entity.setPublishedAt(content.publishedAt());
        entity.setFinalScore(content.finalScore());
        entity.setPopularityScore(content.popularityScore());
        entity.setRelevanceScore(content.relevanceScore());
        entity.setCreatedAt(content.createdAt());
        entity.setUpdatedAt(content.updatedAt());
        return entity;
    }

    // --- Helper: ContentEntity → Content ---

    private static Content toDomain(ContentEntity entity) {
        return new Content(
                entity.getId(),
                entity.getProvider(),
                entity.getExternalId(),
                entity.getTitle(),
                entity.getDescription(),
                entity.getType(),
                entity.getViews(),
                entity.getLikes(),
                entity.getReadingTime(),
                entity.getReactions(),
                entity.getDuration(),
                entity.getTags() != null ? List.of(entity.getTags()) : List.of(),
                entity.getPublishedAt(),
                entity.getFinalScore(),
                entity.getPopularityScore(),
                entity.getRelevanceScore(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    // --- Helper: build a fully-populated Content ---

    private Content buildContent(String description, List<String> tags) {
        return new Content(
                UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890"),
                "provider1-json",
                "ext-001",
                "Sample Video Title",
                description,
                ContentType.VIDEO,
                15000L,
                1200L,
                0,
                0L,
                "PT10M30S",
                tags,
                Instant.parse("2024-06-15T10:30:00Z"),
                46.3,
                27.0,
                0.0,
                Instant.parse("2024-06-15T11:00:00Z"),
                Instant.parse("2024-06-16T08:00:00Z")
        );
    }

    @Test
    @DisplayName("Round-trip: Content → ContentEntity → Content preserves all fields")
    void roundTrip_allFieldsPreserved() {
        List<String> tags = List.of("java", "spring", "tutorial");
        Content original = buildContent("A detailed description of the video.", tags);

        ContentEntity entity = toEntity(original);
        Content restored = toDomain(entity);

        assertEquals(original.id(), restored.id());
        assertEquals(original.provider(), restored.provider());
        assertEquals(original.externalId(), restored.externalId());
        assertEquals(original.title(), restored.title());
        assertEquals(original.description(), restored.description());
        assertEquals(original.type(), restored.type());
        assertEquals(original.views(), restored.views());
        assertEquals(original.likes(), restored.likes());
        assertEquals(original.readingTime(), restored.readingTime());
        assertEquals(original.reactions(), restored.reactions());
        assertEquals(original.duration(), restored.duration());
        assertEquals(original.tags(), restored.tags());
        assertEquals(original.publishedAt(), restored.publishedAt());
        assertEquals(original.finalScore(), restored.finalScore(), 1e-9);
        assertEquals(original.popularityScore(), restored.popularityScore(), 1e-9);
        assertEquals(original.relevanceScore(), restored.relevanceScore(), 1e-9);
        assertEquals(original.createdAt(), restored.createdAt());
        assertEquals(original.updatedAt(), restored.updatedAt());
    }

    @Test
    @DisplayName("Round-trip preserves tag ordering")
    void roundTrip_tagsOrderPreserved() {
        List<String> tags = List.of("zulu", "alpha", "mike", "bravo");
        Content original = buildContent("desc", tags);

        ContentEntity entity = toEntity(original);
        Content restored = toDomain(entity);

        assertEquals(tags, restored.tags(), "Tags must preserve insertion order");
        assertIterableEquals(original.tags(), restored.tags());
    }

    @Test
    @DisplayName("Round-trip preserves null description")
    void roundTrip_nullDescriptionPreserved() {
        Content original = buildContent(null, List.of("tag1"));

        ContentEntity entity = toEntity(original);
        Content restored = toDomain(entity);

        assertNull(restored.description(), "Null description must be preserved through round-trip");
    }

    @Test
    @DisplayName("Round-trip preserves empty tags list")
    void roundTrip_emptyTagsPreserved() {
        Content original = buildContent("desc", List.of());

        ContentEntity entity = toEntity(original);
        Content restored = toDomain(entity);

        assertNotNull(restored.tags());
        assertTrue(restored.tags().isEmpty(), "Empty tags list must be preserved");
    }

    @Test
    @DisplayName("Round-trip preserves all numeric fields exactly for TEXT type")
    void roundTrip_textTypeNumericFields() {
        Content original = new Content(
                UUID.randomUUID(),
                "provider2-xml",
                "ext-article-42",
                "Deep Dive into Reactive Streams",
                "An in-depth article about reactive programming.",
                ContentType.TEXT,
                500L,
                30L,
                12,
                250L,
                null,
                List.of("reactive", "java"),
                Instant.parse("2024-01-10T08:00:00Z"),
                303.25,
                150.0,
                0.85,
                Instant.parse("2024-01-10T09:00:00Z"),
                Instant.parse("2024-03-01T12:00:00Z")
        );

        ContentEntity entity = toEntity(original);
        Content restored = toDomain(entity);

        assertEquals(original.views(), restored.views());
        assertEquals(original.likes(), restored.likes());
        assertEquals(original.readingTime(), restored.readingTime());
        assertEquals(original.reactions(), restored.reactions());
        assertEquals(original.finalScore(), restored.finalScore(), 1e-9);
        assertEquals(original.popularityScore(), restored.popularityScore(), 1e-9);
        assertEquals(original.relevanceScore(), restored.relevanceScore(), 1e-9);
        assertEquals(original.type(), restored.type());
        assertNull(restored.duration(), "Null duration must be preserved for text content");
    }

    @Test
    @DisplayName("Round-trip preserves Instant fields with nanosecond precision")
    void roundTrip_instantFieldsPrecision() {
        Instant publishedAt = Instant.parse("2024-12-25T23:59:59.123456789Z");
        Instant createdAt = Instant.parse("2024-12-26T00:00:00.000000001Z");
        Instant updatedAt = Instant.parse("2024-12-26T12:30:45.999999999Z");

        Content original = new Content(
                UUID.randomUUID(),
                "provider1-json",
                "ext-nano-1",
                "Nanosecond Precision Test",
                null,
                ContentType.VIDEO,
                100L,
                10L,
                0,
                0L,
                "PT5M",
                List.of(),
                publishedAt,
                10.0,
                5.0,
                0.0,
                createdAt,
                updatedAt
        );

        ContentEntity entity = toEntity(original);
        Content restored = toDomain(entity);

        assertEquals(publishedAt, restored.publishedAt());
        assertEquals(createdAt, restored.createdAt());
        assertEquals(updatedAt, restored.updatedAt());
    }

    @Test
    @DisplayName("toEntity converts List<String> tags to String[] correctly")
    void toEntity_tagsConvertedToArray() {
        List<String> tags = List.of("spring-boot", "microservices", "docker");
        Content content = buildContent("desc", tags);

        ContentEntity entity = toEntity(content);

        assertNotNull(entity.getTags());
        assertArrayEquals(new String[]{"spring-boot", "microservices", "docker"}, entity.getTags());
    }

    @Test
    @DisplayName("toDomain converts String[] tags to List<String> correctly")
    void toDomain_tagsConvertedToList() {
        ContentEntity entity = new ContentEntity();
        entity.setId(UUID.randomUUID());
        entity.setProvider("provider1-json");
        entity.setExternalId("ext-99");
        entity.setTitle("Test Title");
        entity.setDescription("desc");
        entity.setType(ContentType.VIDEO);
        entity.setViews(100L);
        entity.setLikes(10L);
        entity.setReadingTime(0);
        entity.setReactions(0L);
        entity.setDuration("PT3M");
        entity.setTags(new String[]{"tag-a", "tag-b", "tag-c"});
        entity.setPublishedAt(Instant.now());
        entity.setFinalScore(5.0);
        entity.setPopularityScore(3.0);
        entity.setRelevanceScore(0.0);
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());

        Content domain = toDomain(entity);

        assertEquals(List.of("tag-a", "tag-b", "tag-c"), domain.tags());
    }

    @Test
    @DisplayName("Round-trip with zero-value numeric fields")
    void roundTrip_zeroValueNumericFields() {
        Content original = new Content(
                UUID.randomUUID(),
                "provider1-json",
                "ext-zero",
                "Zero Metrics Content",
                null,
                ContentType.VIDEO,
                0L,
                0L,
                0,
                0L,
                null,
                List.of(),
                Instant.parse("2024-01-01T00:00:00Z"),
                0.0,
                0.0,
                0.0,
                Instant.parse("2024-01-01T00:00:00Z"),
                Instant.parse("2024-01-01T00:00:00Z")
        );

        ContentEntity entity = toEntity(original);
        Content restored = toDomain(entity);

        assertEquals(0L, restored.views());
        assertEquals(0L, restored.likes());
        assertEquals(0, restored.readingTime());
        assertEquals(0L, restored.reactions());
        assertEquals(0.0, restored.finalScore(), 1e-9);
        assertEquals(0.0, restored.popularityScore(), 1e-9);
        assertEquals(0.0, restored.relevanceScore(), 1e-9);
    }

    @Test
    @DisplayName("Round-trip with large numeric values")
    void roundTrip_largeNumericValues() {
        Content original = new Content(
                UUID.randomUUID(),
                "provider2-xml",
                "ext-large",
                "Viral Content",
                "Extremely popular content",
                ContentType.VIDEO,
                Long.MAX_VALUE / 2,
                Long.MAX_VALUE / 4,
                Integer.MAX_VALUE,
                Long.MAX_VALUE / 3,
                "PT2H30M",
                List.of("viral", "trending"),
                Instant.parse("2024-06-01T00:00:00Z"),
                Double.MAX_VALUE / 2,
                Double.MAX_VALUE / 4,
                Double.MAX_VALUE / 8,
                Instant.parse("2024-06-01T00:00:01Z"),
                Instant.parse("2024-06-02T00:00:00Z")
        );

        ContentEntity entity = toEntity(original);
        Content restored = toDomain(entity);

        assertEquals(original.views(), restored.views());
        assertEquals(original.likes(), restored.likes());
        assertEquals(original.readingTime(), restored.readingTime());
        assertEquals(original.reactions(), restored.reactions());
        assertEquals(original.finalScore(), restored.finalScore(), 1e-9);
        assertEquals(original.popularityScore(), restored.popularityScore(), 1e-9);
        assertEquals(original.relevanceScore(), restored.relevanceScore(), 1e-9);
    }
}
