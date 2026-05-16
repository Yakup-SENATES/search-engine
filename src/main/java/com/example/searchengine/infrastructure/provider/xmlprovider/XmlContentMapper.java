package com.example.searchengine.infrastructure.provider.xmlprovider;

import com.example.searchengine.domain.content.ContentType;
import com.example.searchengine.domain.provider.RawContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;

/**
 * Maps an {@link XmlItemDto} to a {@link RawContent} domain record, applying
 * the field mapping defined in REQ 3.2 and the {@code article → TEXT} type
 * normalization via {@link ContentType#fromProviderValue(String)} (REQ 3.3).
 *
 * <p>Per-item parse failures are logged and the item is skipped (REQ 3.5).</p>
 */
@Component
public class XmlContentMapper {

    private static final Logger log = LoggerFactory.getLogger(XmlContentMapper.class);

    /**
     * Attempts to map a single XML item DTO to a {@link RawContent}.
     *
     * @param item the parsed XML item DTO
     * @return an {@link Optional} containing the mapped content, or empty if the item is invalid
     */
    public Optional<RawContent> map(XmlItemDto item) {
        try {
            if (item.getId() == null || item.getId().isBlank()) {
                log.warn("XML item skipped: missing or blank id");
                return Optional.empty();
            }

            if (item.getHeadline() == null || item.getHeadline().isBlank()) {
                log.warn("XML item skipped: missing or blank headline, id={}", item.getId());
                return Optional.empty();
            }

            if (item.getType() == null || item.getType().isBlank()) {
                log.warn("XML item skipped: missing or blank type, id={}", item.getId());
                return Optional.empty();
            }

            // Validate type is recognized (article → TEXT, video → VIDEO)
            ContentType.fromProviderValue(item.getType());

            // Parse publication_date
            if (item.getPublicationDate() == null || item.getPublicationDate().isBlank()) {
                log.warn("XML item skipped: missing publication_date, id={}", item.getId());
                return Optional.empty();
            }

            Instant publishedAt;
            try {
                publishedAt = Instant.parse(item.getPublicationDate());
            } catch (DateTimeParseException e) {
                log.warn("XML item skipped: invalid publication_date='{}', id={}",
                        item.getPublicationDate(), item.getId());
                return Optional.empty();
            }

            // Default absent stats to zero (REQ 3.5)
            long views = 0;
            long likes = 0;
            int readingTime = 0;
            long reactions = 0;

            if (item.getStats() != null) {
                views = item.getStats().getViews();
                likes = item.getStats().getLikes();
                readingTime = item.getStats().getReadingTime();
                reactions = item.getStats().getReactions();
            }

            // Default absent categories to empty list (REQ 3.5)
            List<String> tags = item.getCategories() != null ? item.getCategories() : List.of();

            return Optional.of(new RawContent(
                    item.getId(),
                    item.getHeadline(),
                    null, // XML provider does not supply description
                    item.getType(),
                    views,
                    likes,
                    readingTime,
                    reactions,
                    null, // XML provider does not supply duration
                    tags,
                    publishedAt
            ));
        } catch (IllegalArgumentException e) {
            log.warn("XML item skipped: {}, id={}", e.getMessage(), item.getId());
            return Optional.empty();
        } catch (Exception e) {
            log.warn("XML item skipped: unexpected error, id={}", item.getId(), e);
            return Optional.empty();
        }
    }
}
