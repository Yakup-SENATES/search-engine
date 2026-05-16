package com.example.searchengine.infrastructure.provider.jsonprovider;

import com.example.searchengine.domain.provider.RawContent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import net.jqwik.api.*;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based round-trip test for the JSON provider DTO serialization/deserialization
 * and mapper correctness.
 *
 * <p><b>Validates: Requirements 2.2, 2.3, 3.1, 3.2, 3.3, 3.4, 3.5, 4.1, 23.4</b></p>
 */
class JsonProviderRoundTripPropertyTest {

    private final ObjectMapper objectMapper;
    private final JsonContentMapper mapper;

    JsonProviderRoundTripPropertyTest() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.mapper = new JsonContentMapper();
    }

    @Property(tries = 100)
    @Label("Feature: search-engine-service, Property 1: Provider round-trip equivalence")
    void jsonDtoRoundTripPreservesAllFields(@ForAll("validJsonContentDto") JsonContentDto original) throws Exception {
        // Serialize to JSON
        String json = objectMapper.writeValueAsString(original);

        // Deserialize back to DTO
        JsonContentDto deserialized = objectMapper.readValue(json, JsonContentDto.class);

        // Assert field-level equality (round-trip)
        assertThat(deserialized.id()).isEqualTo(original.id());
        assertThat(deserialized.title()).isEqualTo(original.title());
        assertThat(deserialized.type()).isEqualTo(original.type());
        assertThat(deserialized.metrics()).isNotNull();
        assertThat(deserialized.metrics().views()).isEqualTo(original.metrics().views());
        assertThat(deserialized.metrics().likes()).isEqualTo(original.metrics().likes());
        assertThat(deserialized.metrics().duration()).isEqualTo(original.metrics().duration());
        assertThat(deserialized.publishedAt()).isEqualTo(original.publishedAt());
        assertThat(deserialized.tags()).isEqualTo(original.tags());

        // Assert mapper produces correct RawContent per documented mapping (REQ 2.3)
        RawContent rawContent = mapper.map(deserialized).orElseThrow(
                () -> new AssertionError("Mapper should successfully map a valid DTO"));

        // id → externalId
        assertThat(rawContent.externalId()).isEqualTo(original.id());
        // title → title
        assertThat(rawContent.title()).isEqualTo(original.title());
        // type → type (raw, not normalized)
        assertThat(rawContent.type()).isEqualTo(original.type());
        // metrics.views → views
        assertThat(rawContent.views()).isEqualTo(original.metrics().views());
        // metrics.likes → likes
        assertThat(rawContent.likes()).isEqualTo(original.metrics().likes());
        // metrics.duration → duration
        assertThat(rawContent.duration()).isEqualTo(original.metrics().duration());
        // published_at → publishedAt
        assertThat(rawContent.publishedAt()).isEqualTo(original.publishedAt());
        // tags → tags
        assertThat(rawContent.tags()).isEqualTo(original.tags());
        // JSON provider does not supply description, readingTime, reactions
        assertThat(rawContent.description()).isNull();
        assertThat(rawContent.readingTime()).isEqualTo(0);
        assertThat(rawContent.reactions()).isEqualTo(0);
    }

    @Provide
    Arbitrary<JsonContentDto> validJsonContentDto() {
        Arbitrary<String> idArb = Arbitraries.strings().alpha().numeric().ofMinLength(1).ofMaxLength(30);
        Arbitrary<String> titleArb = Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(50);
        Arbitrary<String> typeArb = Arbitraries.of("video");
        Arbitrary<Long> viewsArb = Arbitraries.longs().between(0, 1_000_000);
        Arbitrary<Long> likesArb = Arbitraries.longs().between(0, 1_000_000);
        Arbitrary<String> durationArb = Arbitraries.of("PT5M", "PT10M", "PT30M", "PT1H", "PT2H30M");
        Arbitrary<Instant> publishedAtArb = Arbitraries.longs()
                .between(946684800L, Instant.now().getEpochSecond()) // from 2000-01-01 to now
                .map(Instant::ofEpochSecond);
        Arbitrary<List<String>> tagsArb = Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(15)
                .list().ofMinSize(0).ofMaxSize(5);

        return Combinators.combine(idArb, titleArb, typeArb, viewsArb, likesArb, durationArb, publishedAtArb, tagsArb)
                .as((id, title, type, views, likes, duration, publishedAt, tags) ->
                        new JsonContentDto(
                                id,
                                title,
                                type,
                                new JsonMetrics(views, likes, duration),
                                publishedAt,
                                tags
                        )
                );
    }
}
