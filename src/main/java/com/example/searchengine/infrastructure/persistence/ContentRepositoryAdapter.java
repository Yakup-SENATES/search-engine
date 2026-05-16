package com.example.searchengine.infrastructure.persistence;

import com.example.searchengine.domain.content.Content;
import com.example.searchengine.domain.content.ContentRepository;
import com.example.searchengine.domain.content.ContentRepositoryException;
import com.example.searchengine.domain.content.ContentType;
import com.example.searchengine.domain.content.SearchCriteria;
import com.example.searchengine.domain.content.SearchPage;
import com.example.searchengine.domain.content.SortField;
import com.example.searchengine.domain.content.UpsertOutcome;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Infrastructure adapter implementing the domain {@link ContentRepository} port.
 *
 * <p>Delegates to {@link ContentJpaRepository} for persistence operations and maps
 * between the domain {@link Content} record and the JPA {@link ContentEntity}.
 * All {@link DataAccessException}s are translated into
 * {@link ContentRepositoryException} so the domain layer remains decoupled from
 * Spring/JPA specifics (REQ 14.3, 22.3).</p>
 *
 * <p>REQ 5.1, 5.2, 5.6, 5.7, 14.3, 22.3</p>
 */
@Component
public class ContentRepositoryAdapter implements ContentRepository {

    private final ContentJpaRepository jpaRepository;

    public ContentRepositoryAdapter(ContentJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public UpsertOutcome upsert(Content content) {
        try {
            boolean inserted = jpaRepository.upsertNative(
                    content.id(),
                    content.provider(),
                    content.externalId(),
                    content.title(),
                    content.description(),
                    content.type().name(),
                    content.views(),
                    content.likes(),
                    content.readingTime(),
                    content.reactions(),
                    content.duration(),
                    content.tags().toArray(new String[0]),
                    content.publishedAt(),
                    content.finalScore(),
                    content.popularityScore(),
                    content.relevanceScore()
            );
            return inserted ? UpsertOutcome.INSERTED : UpsertOutcome.UPDATED;
        } catch (DataAccessException ex) {
            throw new ContentRepositoryException("Failed to upsert content id=" + content.id(), ex);
        }
    }

    @Override
    public SearchPage search(SearchCriteria criteria) {
        try {
            String typeFilter = criteria.type() != null ? criteria.type().name() : null;
            String sort = criteria.sort().name();
            int offset = (criteria.page() - 1) * criteria.limit();

            List<ContentEntity> entities = jpaRepository.searchFts(
                    criteria.q(),
                    typeFilter,
                    sort,
                    offset,
                    criteria.limit()
            );

            long total = jpaRepository.countFts(criteria.q(), typeFilter);

            List<Content> items = entities.stream()
                    .map(ContentRepositoryAdapter::toDomain)
                    .toList();

            return new SearchPage(items, total);
        } catch (DataAccessException ex) {
            throw new ContentRepositoryException("Failed to search content q=" + criteria.q(), ex);
        }
    }

    @Override
    public Optional<Content> findById(UUID id) {
        try {
            return jpaRepository.findById(id).map(ContentRepositoryAdapter::toDomain);
        } catch (DataAccessException ex) {
            throw new ContentRepositoryException("Failed to find content id=" + id, ex);
        }
    }

    @Override
    public SearchPage listTop(SortField sort, ContentType type, int limit) {
        if (sort == null) {
            throw new IllegalArgumentException("sort must not be null");
        }
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
        try {
            String typeFilter = type != null ? type.name() : null;
            List<ContentEntity> entities = jpaRepository.listTop(typeFilter, sort.name(), limit);
            long total = jpaRepository.countByType(typeFilter);

            List<Content> items = entities.stream()
                    .map(ContentRepositoryAdapter::toDomain)
                    .toList();

            return new SearchPage(items, total);
        } catch (DataAccessException ex) {
            throw new ContentRepositoryException("Failed to list top content", ex);
        }
    }

    // --- Static mapping helpers ---

    /**
     * Maps a JPA {@link ContentEntity} to the domain {@link Content} record.
     */
    static Content toDomain(ContentEntity entity) {
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
                entity.getTags() != null ? Arrays.asList(entity.getTags()) : List.of(),
                entity.getPublishedAt(),
                entity.getFinalScore(),
                entity.getPopularityScore(),
                entity.getRelevanceScore(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    /**
     * Maps a domain {@link Content} record to a JPA {@link ContentEntity}.
     */
    static ContentEntity toEntity(Content content) {
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
}
