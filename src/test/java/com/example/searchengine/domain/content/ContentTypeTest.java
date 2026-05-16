package com.example.searchengine.domain.content;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ContentType#fromProviderValue(String)}.
 * Validates Requirements 3.3, 3.4, 4.5.
 */
class ContentTypeTest {

    // --- Valid mappings (REQ 3.3, 3.4) ---

    @Test
    @DisplayName("'video' maps to VIDEO")
    void video_mapsToVIDEO() {
        assertEquals(ContentType.VIDEO, ContentType.fromProviderValue("video"));
    }

    @Test
    @DisplayName("'VIDEO' (uppercase) maps to VIDEO (case-insensitive)")
    void videoUppercase_mapsToVIDEO() {
        assertEquals(ContentType.VIDEO, ContentType.fromProviderValue("VIDEO"));
    }

    @Test
    @DisplayName("'Video' (mixed case) maps to VIDEO")
    void videoMixedCase_mapsToVIDEO() {
        assertEquals(ContentType.VIDEO, ContentType.fromProviderValue("Video"));
    }

    @Test
    @DisplayName("'text' maps to TEXT")
    void text_mapsToTEXT() {
        assertEquals(ContentType.TEXT, ContentType.fromProviderValue("text"));
    }

    @Test
    @DisplayName("'TEXT' (uppercase) maps to TEXT (case-insensitive)")
    void textUppercase_mapsToTEXT() {
        assertEquals(ContentType.TEXT, ContentType.fromProviderValue("TEXT"));
    }

    @Test
    @DisplayName("'article' maps to TEXT (REQ 3.3)")
    void article_mapsToTEXT() {
        assertEquals(ContentType.TEXT, ContentType.fromProviderValue("article"));
    }

    @Test
    @DisplayName("'Article' (mixed case) maps to TEXT")
    void articleMixedCase_mapsToTEXT() {
        assertEquals(ContentType.TEXT, ContentType.fromProviderValue("Article"));
    }

    @Test
    @DisplayName("'ARTICLE' (uppercase) maps to TEXT")
    void articleUppercase_mapsToTEXT() {
        assertEquals(ContentType.TEXT, ContentType.fromProviderValue("ARTICLE"));
    }

    // --- Whitespace trimming ---

    @Test
    @DisplayName("' video ' (with spaces) maps to VIDEO after trimming")
    void videoWithSpaces_mapsToVIDEO() {
        assertEquals(ContentType.VIDEO, ContentType.fromProviderValue("  video  "));
    }

    @Test
    @DisplayName("' article ' (with spaces) maps to TEXT after trimming")
    void articleWithSpaces_mapsToTEXT() {
        assertEquals(ContentType.TEXT, ContentType.fromProviderValue("  article  "));
    }

    // --- Null input (REQ 4.5) ---

    @Test
    @DisplayName("null throws IllegalArgumentException")
    void null_throwsIllegalArgumentException() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ContentType.fromProviderValue(null));
        assertTrue(ex.getMessage().contains("null"));
    }

    // --- Unknown types throw (REQ 4.5) ---

    @ParameterizedTest(name = "Unknown type ''{0}'' throws IllegalArgumentException")
    @ValueSource(strings = {"podcast", "image", "audio", "blog", "unknown", ""})
    @DisplayName("Unknown raw types throw IllegalArgumentException")
    void unknownType_throwsIllegalArgumentException(String rawType) {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ContentType.fromProviderValue(rawType));
        assertTrue(ex.getMessage().contains("unknown type") || ex.getMessage().contains("null"));
    }
}
