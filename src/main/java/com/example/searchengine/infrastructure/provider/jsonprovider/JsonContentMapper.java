package com.example.searchengine.infrastructure.provider.jsonprovider;

import com.example.searchengine.domain.provider.RawContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/**
 * Maps a {@link JsonContentDto} to a {@link RawContent} domain record,
 * applying the field mapping defined in REQ 2.3:
 *
 * <ul>
 *   <li>{@code id} → {@code externalId}</li>
 *   <li>{@code title} → {@code title}</li>
 *   <li>{@code type} → {@code type}</li>
 *   <li>{@code metrics.views} → {@code views}</li>
 *   <li>{@code metrics.likes} → {@code likes}</li>
 *   <li>{@code metrics.duration} → {@code duration}</li>
 *   <li>{@code published_at} → {@code publishedAt}</li>
 *   <li>{@code tags} → {@code tags}</li>
 * </ul>
 *
 * <p>Per-item failures (missing required fields) are logged and the item is skipped (REQ 2.5).</p>
 */
public class JsonContentMapper {

    private static final Logger log = LoggerFactory.getLogger(JsonContentMapper.class);

    /**
     * Attempts to map a single DTO to a {@link RawContent}.
     * Returns {@link Optional#empty()} if a required field is missing or invalid,
     * logging the failure with the offending field path and item identifier (REQ 2.5, REQ 16.4).
     *
     * @param dto the parsed JSON content item
     * @return mapped RawContent, or empty if the item is invalid
     */
    public Optional<RawContent> map(JsonContentDto dto) {
        try {
            if (dto.id() == null || dto.id().isBlank()) {
                log.warn("JSON item skipped: missing required field 'id'");
                return Optional.empty();
            }
            if (dto.title() == null || dto.title().isBlank()) {
                log.warn("JSON item skipped: missing required field 'title', id={}", dto.id());
                return Optional.empty();
            }
            if (dto.type() == null || dto.type().isBlank()) {
                log.warn("JSON item skipped: missing required field 'type', id={}", dto.id());
                return Optional.empty();
            }
            if (dto.metrics() == null) {
                log.warn("JSON item skipped: missing required field 'metrics', id={}", dto.id());
                return Optional.empty();
            }
            if (dto.metrics().duration() == null || dto.metrics().duration().isBlank()) {
                log.warn("JSON item skipped: missing required field 'metrics.duration', id={}", dto.id());
                return Optional.empty();
            }
            if (dto.publishedAt() == null) {
                log.warn("JSON item skipped: missing required field 'published_at', id={}", dto.id());
                return Optional.empty();
            }

            List<String> tags = dto.tags() != null ? dto.tags() : List.of();

            RawContent rawContent = new RawContent(
                    dto.id(),
                    dto.title(),
                    null,                       // JSON provider has no description field
                    dto.type(),
                    dto.metrics().views(),
                    dto.metrics().likes(),
                    0,                          // readingTime not applicable for JSON provider
                    0,                          // reactions not applicable for JSON provider
                    dto.metrics().duration(),
                    tags,
                    dto.publishedAt()
            );

            return Optional.of(rawContent);
        } catch (Exception e) {
            String itemId = dto != null && dto.id() != null ? dto.id() : "unknown";
            log.warn("JSON item skipped: parse failure for id={}, cause={}", itemId, e.getMessage());
            return Optional.empty();
        }
    }
}
