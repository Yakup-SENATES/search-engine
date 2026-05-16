package com.example.searchengine.domain.content;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link Content} compact-constructor invariants.
 * Validates Requirements 3.3, 3.4, 4.3, 4.4, 4.5.
 */
class ContentTest {

    private static final UUID ID = UUID.randomUUID();
    private static final String PROVIDER = "provider1-json";
    private static final String EXTERNAL_ID = "ext-123";
    private static final String TITLE = "Sample Title";
    private static final String DESCRIPTION = "A description";
    private static final ContentType TYPE = ContentType.VIDEO;
    private static final long VIEWS = 1000L;
    private static final long LIKES = 100L;
    private static final int READING_TIME = 5;
    private static final long REACTIONS = 50L;
    private static final String DURATION = "PT10M";
    private static final List<String> TAGS = List.of("java", "spring");
    private static final Instant PUBLISHED_AT = Instant.parse("2024-01-15T10:00:00Z");
    private static final Instant CREATED_AT = Instant.now();
    private static final Instant UPDATED_AT = Instant.now();

    /**
     * Helper to build a valid Content instance.
     */
    private Content validContent() {
        return new Content(
                ID, PROVIDER, EXTERNAL_ID, TITLE, DESCRIPTION, TYPE,
                VIEWS, LIKES, READING_TIME, REACTIONS, DURATION, TAGS,
                PUBLISHED_AT, 0.0, 0.0, 0.0, CREATED_AT, UPDATED_AT
        );
    }

    // --- Happy path ---

    @Test
    @DisplayName("Valid Content is constructed successfully")
    void validContent_isConstructedSuccessfully() {
        Content content = validContent();
        assertEquals(TITLE, content.title());
        assertEquals(TYPE, content.type());
        assertEquals(VIEWS, content.views());
        assertEquals(PUBLISHED_AT, content.publishedAt());
    }

    @Test
    @DisplayName("Null tags are normalized to empty list")
    void nullTags_normalizedToEmptyList() {
        Content content = new Content(
                ID, PROVIDER, EXTERNAL_ID, TITLE, DESCRIPTION, TYPE,
                VIEWS, LIKES, READING_TIME, REACTIONS, DURATION, null,
                PUBLISHED_AT, 0.0, 0.0, 0.0, CREATED_AT, UPDATED_AT
        );
        assertNotNull(content.tags());
        assertTrue(content.tags().isEmpty());
    }

    @Test
    @DisplayName("Tags list is defensively copied (immutable)")
    void tags_areDefensivelyCopied() {
        Content content = validContent();
        assertThrows(UnsupportedOperationException.class, () -> content.tags().add("new-tag"));
    }

    // --- Provider validation (REQ 4.2) ---

    @Test
    @DisplayName("Null provider throws IllegalArgumentException")
    void nullProvider_throws() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                new Content(ID, null, EXTERNAL_ID, TITLE, DESCRIPTION, TYPE,
                        VIEWS, LIKES, READING_TIME, REACTIONS, DURATION, TAGS,
                        PUBLISHED_AT, 0.0, 0.0, 0.0, CREATED_AT, UPDATED_AT));
        assertEquals("provider", ex.getMessage());
    }

    @Test
    @DisplayName("Blank provider throws IllegalArgumentException")
    void blankProvider_throws() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                new Content(ID, "   ", EXTERNAL_ID, TITLE, DESCRIPTION, TYPE,
                        VIEWS, LIKES, READING_TIME, REACTIONS, DURATION, TAGS,
                        PUBLISHED_AT, 0.0, 0.0, 0.0, CREATED_AT, UPDATED_AT));
        assertEquals("provider", ex.getMessage());
    }

    // --- ExternalId validation (REQ 4.2) ---

    @Test
    @DisplayName("Null externalId throws IllegalArgumentException")
    void nullExternalId_throws() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                new Content(ID, PROVIDER, null, TITLE, DESCRIPTION, TYPE,
                        VIEWS, LIKES, READING_TIME, REACTIONS, DURATION, TAGS,
                        PUBLISHED_AT, 0.0, 0.0, 0.0, CREATED_AT, UPDATED_AT));
        assertEquals("externalId", ex.getMessage());
    }

    @Test
    @DisplayName("Blank externalId throws IllegalArgumentException")
    void blankExternalId_throws() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                new Content(ID, PROVIDER, "", TITLE, DESCRIPTION, TYPE,
                        VIEWS, LIKES, READING_TIME, REACTIONS, DURATION, TAGS,
                        PUBLISHED_AT, 0.0, 0.0, 0.0, CREATED_AT, UPDATED_AT));
        assertEquals("externalId", ex.getMessage());
    }

    // --- Title validation (REQ 4.3) ---

    @Test
    @DisplayName("Null title throws IllegalArgumentException")
    void nullTitle_throws() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                new Content(ID, PROVIDER, EXTERNAL_ID, null, DESCRIPTION, TYPE,
                        VIEWS, LIKES, READING_TIME, REACTIONS, DURATION, TAGS,
                        PUBLISHED_AT, 0.0, 0.0, 0.0, CREATED_AT, UPDATED_AT));
        assertEquals("title", ex.getMessage());
    }

    @Test
    @DisplayName("Blank title throws IllegalArgumentException")
    void blankTitle_throws() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                new Content(ID, PROVIDER, EXTERNAL_ID, "   ", DESCRIPTION, TYPE,
                        VIEWS, LIKES, READING_TIME, REACTIONS, DURATION, TAGS,
                        PUBLISHED_AT, 0.0, 0.0, 0.0, CREATED_AT, UPDATED_AT));
        assertEquals("title", ex.getMessage());
    }

    // --- Type validation (REQ 4.5) ---

    @Test
    @DisplayName("Null type throws IllegalArgumentException")
    void nullType_throws() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                new Content(ID, PROVIDER, EXTERNAL_ID, TITLE, DESCRIPTION, null,
                        VIEWS, LIKES, READING_TIME, REACTIONS, DURATION, TAGS,
                        PUBLISHED_AT, 0.0, 0.0, 0.0, CREATED_AT, UPDATED_AT));
        assertEquals("type", ex.getMessage());
    }

    // --- Metrics validation (REQ 4.4) ---

    @Test
    @DisplayName("Negative views throws IllegalArgumentException")
    void negativeViews_throws() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                new Content(ID, PROVIDER, EXTERNAL_ID, TITLE, DESCRIPTION, TYPE,
                        -1L, LIKES, READING_TIME, REACTIONS, DURATION, TAGS,
                        PUBLISHED_AT, 0.0, 0.0, 0.0, CREATED_AT, UPDATED_AT));
        assertTrue(ex.getMessage().contains("metrics"));
    }

    @Test
    @DisplayName("Negative likes throws IllegalArgumentException")
    void negativeLikes_throws() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                new Content(ID, PROVIDER, EXTERNAL_ID, TITLE, DESCRIPTION, TYPE,
                        VIEWS, -1L, READING_TIME, REACTIONS, DURATION, TAGS,
                        PUBLISHED_AT, 0.0, 0.0, 0.0, CREATED_AT, UPDATED_AT));
        assertTrue(ex.getMessage().contains("metrics"));
    }

    @Test
    @DisplayName("Negative readingTime throws IllegalArgumentException")
    void negativeReadingTime_throws() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                new Content(ID, PROVIDER, EXTERNAL_ID, TITLE, DESCRIPTION, TYPE,
                        VIEWS, LIKES, -1, REACTIONS, DURATION, TAGS,
                        PUBLISHED_AT, 0.0, 0.0, 0.0, CREATED_AT, UPDATED_AT));
        assertTrue(ex.getMessage().contains("metrics"));
    }

    @Test
    @DisplayName("Negative reactions throws IllegalArgumentException")
    void negativeReactions_throws() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                new Content(ID, PROVIDER, EXTERNAL_ID, TITLE, DESCRIPTION, TYPE,
                        VIEWS, LIKES, READING_TIME, -1L, DURATION, TAGS,
                        PUBLISHED_AT, 0.0, 0.0, 0.0, CREATED_AT, UPDATED_AT));
        assertTrue(ex.getMessage().contains("metrics"));
    }

    // --- PublishedAt validation (REQ 4.3) ---

    @Test
    @DisplayName("Null publishedAt throws IllegalArgumentException")
    void nullPublishedAt_throws() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                new Content(ID, PROVIDER, EXTERNAL_ID, TITLE, DESCRIPTION, TYPE,
                        VIEWS, LIKES, READING_TIME, REACTIONS, DURATION, TAGS,
                        null, 0.0, 0.0, 0.0, CREATED_AT, UPDATED_AT));
        assertEquals("publishedAt", ex.getMessage());
    }

    // --- Zero metrics are valid ---

    @Test
    @DisplayName("Zero metrics are accepted")
    void zeroMetrics_areAccepted() {
        Content content = new Content(
                ID, PROVIDER, EXTERNAL_ID, TITLE, DESCRIPTION, TYPE,
                0L, 0L, 0, 0L, DURATION, TAGS,
                PUBLISHED_AT, 0.0, 0.0, 0.0, CREATED_AT, UPDATED_AT
        );
        assertEquals(0L, content.views());
        assertEquals(0L, content.likes());
        assertEquals(0, content.readingTime());
        assertEquals(0L, content.reactions());
    }
}
