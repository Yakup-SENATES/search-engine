package com.example.searchengine.infrastructure.provider.xmlprovider;

import com.example.searchengine.domain.provider.RawContent;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.Marshaller;
import jakarta.xml.bind.Unmarshaller;
import net.jqwik.api.*;

import java.io.StringReader;
import java.io.StringWriter;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based round-trip test for the XML provider DTO marshalling/unmarshalling
 * and mapper correctness, including the {@code article → text} type rewrite.
 *
 * <p><b>Validates: Requirements 2.2, 2.3, 3.1, 3.2, 3.3, 3.4, 3.5, 4.1, 23.4</b></p>
 */
class XmlProviderRoundTripPropertyTest {

    private final JAXBContext jaxbContext;
    private final XmlContentMapper mapper;

    XmlProviderRoundTripPropertyTest() throws Exception {
        this.jaxbContext = JAXBContext.newInstance(XmlFeedDto.class);
        this.mapper = new XmlContentMapper();
    }

    @Property(tries = 100)
    @Label("Feature: search-engine-service, Property 1: Provider round-trip equivalence")
    void xmlDtoRoundTripPreservesAllFields(@ForAll("validXmlItemDto") XmlItemDto original) throws Exception {
        // Wrap in a feed for marshalling
        XmlFeedDto feed = new XmlFeedDto();
        feed.setItems(List.of(original));

        // Marshal to XML string
        Marshaller marshaller = jaxbContext.createMarshaller();
        marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.TRUE);
        StringWriter writer = new StringWriter();
        marshaller.marshal(feed, writer);
        String xml = writer.toString();

        // Unmarshal back to DTO
        Unmarshaller unmarshaller = jaxbContext.createUnmarshaller();
        XmlFeedDto deserializedFeed = (XmlFeedDto) unmarshaller.unmarshal(new StringReader(xml));

        assertThat(deserializedFeed.getItems()).hasSize(1);
        XmlItemDto deserialized = deserializedFeed.getItems().get(0);

        // Assert field-level equality (round-trip)
        assertThat(deserialized.getId()).isEqualTo(original.getId());
        assertThat(deserialized.getHeadline()).isEqualTo(original.getHeadline());
        assertThat(deserialized.getType()).isEqualTo(original.getType());
        assertThat(deserialized.getPublicationDate()).isEqualTo(original.getPublicationDate());

        if (original.getStats() != null) {
            assertThat(deserialized.getStats()).isNotNull();
            assertThat(deserialized.getStats().getViews()).isEqualTo(original.getStats().getViews());
            assertThat(deserialized.getStats().getLikes()).isEqualTo(original.getStats().getLikes());
            assertThat(deserialized.getStats().getReadingTime()).isEqualTo(original.getStats().getReadingTime());
            assertThat(deserialized.getStats().getReactions()).isEqualTo(original.getStats().getReactions());
        } else {
            // When stats is absent, JAXB may deserialize as null
            assertThat(deserialized.getStats() == null ||
                    (deserialized.getStats().getViews() == 0
                            && deserialized.getStats().getLikes() == 0
                            && deserialized.getStats().getReadingTime() == 0
                            && deserialized.getStats().getReactions() == 0)).isTrue();
        }

        if (original.getCategories() != null && !original.getCategories().isEmpty()) {
            assertThat(deserialized.getCategories()).isEqualTo(original.getCategories());
        } else {
            // Absent categories may deserialize as null or empty
            assertThat(deserialized.getCategories() == null || deserialized.getCategories().isEmpty()).isTrue();
        }

        // Assert mapper produces correct RawContent per documented mapping (REQ 3.2, 3.3)
        RawContent rawContent = mapper.map(deserialized).orElseThrow(
                () -> new AssertionError("Mapper should successfully map a valid DTO"));

        // id → externalId
        assertThat(rawContent.externalId()).isEqualTo(original.getId());
        // headline → title
        assertThat(rawContent.title()).isEqualTo(original.getHeadline());
        // type is passed through as raw string in RawContent
        assertThat(rawContent.type()).isEqualTo(original.getType());
        // publication_date → publishedAt
        assertThat(rawContent.publishedAt()).isEqualTo(Instant.parse(original.getPublicationDate()));

        // stats mapping with defaults for absent stats (REQ 3.5)
        if (original.getStats() != null) {
            // stats.views → views
            assertThat(rawContent.views()).isEqualTo(original.getStats().getViews());
            // stats.likes → likes
            assertThat(rawContent.likes()).isEqualTo(original.getStats().getLikes());
            // stats.reading_time → readingTime
            assertThat(rawContent.readingTime()).isEqualTo(original.getStats().getReadingTime());
            // stats.reactions → reactions
            assertThat(rawContent.reactions()).isEqualTo(original.getStats().getReactions());
        } else {
            // Absent stats default to zero (REQ 3.5)
            assertThat(rawContent.views()).isEqualTo(0);
            assertThat(rawContent.likes()).isEqualTo(0);
            assertThat(rawContent.readingTime()).isEqualTo(0);
            assertThat(rawContent.reactions()).isEqualTo(0);
        }

        // categories → tags (empty list if absent, REQ 3.5)
        if (original.getCategories() != null && !original.getCategories().isEmpty()) {
            assertThat(rawContent.tags()).isEqualTo(original.getCategories());
        } else {
            assertThat(rawContent.tags()).isEmpty();
        }

        // XML provider does not supply description or duration
        assertThat(rawContent.description()).isNull();
        assertThat(rawContent.duration()).isNull();

        // Verify article → text type rewrite is handled correctly by ContentType (REQ 3.3)
        // The mapper passes the raw type through to RawContent; the normalization layer
        // applies ContentType.fromProviderValue. We verify the mapper preserves the raw type.
        if ("article".equals(original.getType())) {
            assertThat(rawContent.type()).isEqualTo("article");
            // ContentType.fromProviderValue("article") should map to TEXT
            assertThat(com.example.searchengine.domain.content.ContentType.fromProviderValue("article"))
                    .isEqualTo(com.example.searchengine.domain.content.ContentType.TEXT);
        } else if ("video".equals(original.getType())) {
            assertThat(rawContent.type()).isEqualTo("video");
            assertThat(com.example.searchengine.domain.content.ContentType.fromProviderValue("video"))
                    .isEqualTo(com.example.searchengine.domain.content.ContentType.VIDEO);
        }
    }

    @Provide
    Arbitrary<XmlItemDto> validXmlItemDto() {
        Arbitrary<String> idArb = Arbitraries.strings().alpha().numeric().ofMinLength(1).ofMaxLength(30);
        Arbitrary<String> headlineArb = Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(50);
        // Include type=article to test the article → text rewrite (REQ 3.3)
        Arbitrary<String> typeArb = Arbitraries.of("article", "video");
        Arbitrary<String> publicationDateArb = Arbitraries.longs()
                .between(946684800L, Instant.now().getEpochSecond()) // from 2000-01-01 to now
                .map(epoch -> Instant.ofEpochSecond(epoch).toString());
        // Include cases where stats is absent (null) to test REQ 3.5
        Arbitrary<Boolean> hasStatsArb = Arbitraries.of(true, true, true, false); // 75% have stats

        Arbitrary<Long> viewsArb = Arbitraries.longs().between(0, 1_000_000);
        Arbitrary<Long> likesArb = Arbitraries.longs().between(0, 1_000_000);
        Arbitrary<Integer> readingTimeArb = Arbitraries.integers().between(0, 10_000);
        Arbitrary<Long> reactionsArb = Arbitraries.longs().between(0, 1_000_000);

        Arbitrary<List<String>> categoriesArb = Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(15)
                .list().ofMinSize(0).ofMaxSize(5);
        Arbitrary<Boolean> hasCategoriesArb = Arbitraries.of(true, true, true, false); // 75% have categories

        // Use flatAs to stay within the 8-parameter limit of Combinators.combine
        return Combinators.combine(idArb, headlineArb, typeArb, publicationDateArb, hasStatsArb, hasCategoriesArb, categoriesArb)
                .flatAs((id, headline, type, pubDate, hasStats, hasCategories, categories) ->
                        Combinators.combine(viewsArb, likesArb, readingTimeArb, reactionsArb)
                                .as((views, likes, readingTime, reactions) -> {
                                    XmlItemDto item = new XmlItemDto();
                                    item.setId(id);
                                    item.setHeadline(headline);
                                    item.setType(type);
                                    item.setPublicationDate(pubDate);

                                    if (hasStats) {
                                        XmlStatsDto stats = new XmlStatsDto();
                                        stats.setViews(views);
                                        stats.setLikes(likes);
                                        stats.setReadingTime(readingTime);
                                        stats.setReactions(reactions);
                                        item.setStats(stats);
                                    }

                                    if (hasCategories && !categories.isEmpty()) {
                                        item.setCategories(categories);
                                    }

                                    return item;
                                })
                );
    }
}
