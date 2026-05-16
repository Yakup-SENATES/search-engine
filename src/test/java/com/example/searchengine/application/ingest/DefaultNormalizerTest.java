package com.example.searchengine.application.ingest;

import com.example.searchengine.domain.content.Content;
import com.example.searchengine.domain.content.ContentType;
import com.example.searchengine.domain.provider.RawContent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link DefaultNormalizer}.
 *
 * <p>Validates Requirements 4.1, 4.3, 4.4, 4.5, 23.2</p>
 */
class DefaultNormalizerTest {

    private DefaultNormalizer normalizer;

    @BeforeEach
    void setUp() {
        normalizer = new DefaultNormalizer();
    }

    // ─── Success cases ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Success cases")
    class SuccessCases {

        @Test
        @DisplayName("JSON provider: all documented fields populated → Accepted with correct mapping")
        void jsonProvider_allFieldsPopulated_returnsAccepted() {
            RawContent raw = new RawContent(
                    "json-ext-123",
                    "Learn Java 21",
                    "A comprehensive guide to Java 21 features",
                    "video",
                    15000L,
                    1200L,
                    0,
                    0L,
                    "PT15M30S",
                    List.of("java", "programming"),
                    Instant.parse("2024-06-15T10:00:00Z")
            );

            NormalizationResult result = normalizer.normalize("provider1-json", raw);

            assertThat(result).isInstanceOf(NormalizationResult.Accepted.class);
            Content content = ((NormalizationResult.Accepted) result).content();
            assertThat(content.id()).isNotNull();
            assertThat(content.provider()).isEqualTo("provider1-json");
            assertThat(content.externalId()).isEqualTo("json-ext-123");
            assertThat(content.title()).isEqualTo("Learn Java 21");
            assertThat(content.description()).isEqualTo("A comprehensive guide to Java 21 features");
            assertThat(content.type()).isEqualTo(ContentType.VIDEO);
            assertThat(content.views()).isEqualTo(15000L);
            assertThat(content.likes()).isEqualTo(1200L);
            assertThat(content.readingTime()).isZero();
            assertThat(content.reactions()).isZero();
            assertThat(content.duration()).isEqualTo("PT15M30S");
            assertThat(content.tags()).containsExactly("java", "programming");
            assertThat(content.publishedAt()).isEqualTo(Instant.parse("2024-06-15T10:00:00Z"));
        }

        @Test
        @DisplayName("XML provider: all documented fields populated (article type) → Accepted with TEXT type")
        void xmlProvider_allFieldsPopulated_returnsAccepted() {
            RawContent raw = new RawContent(
                    "xml-ext-456",
                    "Understanding Microservices",
                    "Deep dive into microservice architecture",
                    "article",
                    8500L,
                    320L,
                    12,
                    150L,
                    null,
                    List.of("architecture", "microservices", "backend"),
                    Instant.parse("2024-07-20T14:30:00Z")
            );

            NormalizationResult result = normalizer.normalize("provider2-xml", raw);

            assertThat(result).isInstanceOf(NormalizationResult.Accepted.class);
            Content content = ((NormalizationResult.Accepted) result).content();
            assertThat(content.id()).isNotNull();
            assertThat(content.provider()).isEqualTo("provider2-xml");
            assertThat(content.externalId()).isEqualTo("xml-ext-456");
            assertThat(content.title()).isEqualTo("Understanding Microservices");
            assertThat(content.description()).isEqualTo("Deep dive into microservice architecture");
            assertThat(content.type()).isEqualTo(ContentType.TEXT);
            assertThat(content.views()).isEqualTo(8500L);
            assertThat(content.likes()).isEqualTo(320L);
            assertThat(content.readingTime()).isEqualTo(12);
            assertThat(content.reactions()).isEqualTo(150L);
            assertThat(content.duration()).isNull();
            assertThat(content.tags()).containsExactly("architecture", "microservices", "backend");
            assertThat(content.publishedAt()).isEqualTo(Instant.parse("2024-07-20T14:30:00Z"));
        }
    }

    // ─── Rejection cases ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Rejection cases")
    class RejectionCases {

        @Test
        @DisplayName("blank title → Rejected with field 'title'")
        void blankTitle_returnsRejected() {
            RawContent raw = validRawContent().withTitle("   ").build();

            NormalizationResult result = normalizer.normalize("provider1-json", raw);

            assertRejectedWithField(result, "title");
        }

        @Test
        @DisplayName("null title → Rejected with field 'title'")
        void nullTitle_returnsRejected() {
            RawContent raw = validRawContent().withTitle(null).build();

            NormalizationResult result = normalizer.normalize("provider1-json", raw);

            assertRejectedWithField(result, "title");
        }

        @Test
        @DisplayName("unknown type → Rejected with field 'type'")
        void unknownType_returnsRejected() {
            RawContent raw = validRawContent().withType("podcast").build();

            NormalizationResult result = normalizer.normalize("provider1-json", raw);

            assertRejectedWithField(result, "type");
        }

        @Test
        @DisplayName("null type → Rejected with field 'type'")
        void nullType_returnsRejected() {
            RawContent raw = validRawContent().withType(null).build();

            NormalizationResult result = normalizer.normalize("provider1-json", raw);

            assertRejectedWithField(result, "type");
        }

        @Test
        @DisplayName("null publishedAt → Rejected with field 'publishedAt'")
        void nullPublishedAt_returnsRejected() {
            RawContent raw = validRawContent().withPublishedAt(null).build();

            NormalizationResult result = normalizer.normalize("provider1-json", raw);

            assertRejectedWithField(result, "publishedAt");
        }

        @Test
        @DisplayName("missing externalId (null) → Rejected with field 'externalId'")
        void nullExternalId_returnsRejected() {
            RawContent raw = validRawContent().withExternalId(null).build();

            NormalizationResult result = normalizer.normalize("provider1-json", raw);

            assertRejectedWithField(result, "externalId");
        }

        @Test
        @DisplayName("blank externalId → Rejected with field 'externalId'")
        void blankExternalId_returnsRejected() {
            RawContent raw = validRawContent().withExternalId("  ").build();

            NormalizationResult result = normalizer.normalize("provider1-json", raw);

            assertRejectedWithField(result, "externalId");
        }

        @Test
        @DisplayName("negative views → Rejected with field 'views'")
        void negativeViews_returnsRejected() {
            RawContent raw = validRawContent().withViews(-1L).build();

            NormalizationResult result = normalizer.normalize("provider1-json", raw);

            assertRejectedWithField(result, "views");
        }

        @Test
        @DisplayName("negative likes → Rejected with field 'likes'")
        void negativeLikes_returnsRejected() {
            RawContent raw = validRawContent().withLikes(-5L).build();

            NormalizationResult result = normalizer.normalize("provider1-json", raw);

            assertRejectedWithField(result, "likes");
        }

        @Test
        @DisplayName("negative reactions → Rejected with field 'reactions'")
        void negativeReactions_returnsRejected() {
            RawContent raw = validRawContent().withReactions(-10L).build();

            NormalizationResult result = normalizer.normalize("provider1-json", raw);

            assertRejectedWithField(result, "reactions");
        }

        @Test
        @DisplayName("negative readingTime → Rejected with field 'readingTime'")
        void negativeReadingTime_returnsRejected() {
            RawContent raw = validRawContent().withReadingTime(-3).build();

            NormalizationResult result = normalizer.normalize("provider1-json", raw);

            assertRejectedWithField(result, "readingTime");
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────────

    private void assertRejectedWithField(NormalizationResult result, String expectedField) {
        assertThat(result).isInstanceOf(NormalizationResult.Rejected.class);
        NormalizationResult.Rejected rejected = (NormalizationResult.Rejected) result;
        assertThat(rejected.field()).isEqualTo(expectedField);
        assertThat(rejected.reason()).isNotBlank();
    }

    /**
     * Returns a builder-like helper for creating valid RawContent that can be
     * selectively mutated for individual rejection tests.
     */
    private RawContentBuilder validRawContent() {
        return new RawContentBuilder(
                "ext-id-001",
                "Valid Title",
                "Some description",
                "video",
                1000L,
                100L,
                0,
                0L,
                "PT10M",
                List.of("tag1"),
                Instant.parse("2024-01-15T12:00:00Z")
        );
    }

    /**
     * Simple builder wrapper around RawContent to allow selective field mutation
     * for test readability.
     */
    private record RawContentBuilder(
            String externalId,
            String title,
            String description,
            String type,
            long views,
            long likes,
            int readingTime,
            long reactions,
            String duration,
            List<String> tags,
            Instant publishedAt
    ) {
        RawContentBuilder withTitle(String title) {
            return new RawContentBuilder(externalId, title, description, type, views, likes, readingTime, reactions, duration, tags, publishedAt);
        }

        RawContentBuilder withType(String type) {
            return new RawContentBuilder(externalId, title, description, type, views, likes, readingTime, reactions, duration, tags, publishedAt);
        }

        RawContentBuilder withPublishedAt(Instant publishedAt) {
            return new RawContentBuilder(externalId, title, description, type, views, likes, readingTime, reactions, duration, tags, publishedAt);
        }

        RawContentBuilder withExternalId(String externalId) {
            return new RawContentBuilder(externalId, title, description, type, views, likes, readingTime, reactions, duration, tags, publishedAt);
        }

        RawContentBuilder withViews(long views) {
            return new RawContentBuilder(externalId, title, description, type, views, likes, readingTime, reactions, duration, tags, publishedAt);
        }

        RawContentBuilder withLikes(long likes) {
            return new RawContentBuilder(externalId, title, description, type, views, likes, readingTime, reactions, duration, tags, publishedAt);
        }

        RawContentBuilder withReactions(long reactions) {
            return new RawContentBuilder(externalId, title, description, type, views, likes, readingTime, reactions, duration, tags, publishedAt);
        }

        RawContentBuilder withReadingTime(int readingTime) {
            return new RawContentBuilder(externalId, title, description, type, views, likes, readingTime, reactions, duration, tags, publishedAt);
        }

        /**
         * Converts this builder to a RawContent instance for use in tests.
         */
        RawContent build() {
            return new RawContent(externalId, title, description, type, views, likes, readingTime, reactions, duration, tags, publishedAt);
        }
    }

}
