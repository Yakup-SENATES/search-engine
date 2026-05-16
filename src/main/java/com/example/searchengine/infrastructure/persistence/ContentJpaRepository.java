package com.example.searchengine.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link ContentEntity}.
 *
 * <p>Provides a native PostgreSQL upsert using {@code INSERT ... ON CONFLICT ... DO UPDATE}
 * with {@code RETURNING (xmax = 0) AS inserted} to distinguish inserts from updates (REQ 5.2, 5.7),
 * and a parameterized full-text search query using {@code plainto_tsquery('simple', :q)}
 * (REQ 5.1, 5.5, 8.2, 8.5, 9.1, 9.3–9.6, 19.3, 20.3).</p>
 */
@Repository
public interface ContentJpaRepository extends JpaRepository<ContentEntity, UUID> {

    /**
     * Upserts a content row. If the {@code (provider, external_id)} pair already exists,
     * the row is updated; otherwise a new row is inserted.
     *
     * <p>Returns {@code true} if the row was inserted (new), {@code false} if updated.
     * The PostgreSQL {@code xmax = 0} trick distinguishes the two cases.</p>
     *
     * @return {@code true} for insert, {@code false} for update (REQ 5.7)
     */
    @Query(value = """
        INSERT INTO contents (id, provider, external_id, title, description, type,
            views, likes, reading_time, reactions, duration, tags, published_at,
            final_score, popularity_score, relevance_score, created_at, updated_at)
        VALUES (:id, :provider, :externalId, :title, :description, :type,
            :views, :likes, :readingTime, :reactions, :duration, :tags, :publishedAt,
            :finalScore, :popularityScore, :relevanceScore, NOW(), NOW())
        ON CONFLICT (provider, external_id) DO UPDATE SET
            title = EXCLUDED.title,
            description = EXCLUDED.description,
            type = EXCLUDED.type,
            views = EXCLUDED.views,
            likes = EXCLUDED.likes,
            reading_time = EXCLUDED.reading_time,
            reactions = EXCLUDED.reactions,
            duration = EXCLUDED.duration,
            tags = EXCLUDED.tags,
            published_at = EXCLUDED.published_at,
            final_score = EXCLUDED.final_score,
            popularity_score = EXCLUDED.popularity_score,
            relevance_score = EXCLUDED.relevance_score,
            updated_at = NOW()
        RETURNING (xmax = 0) AS inserted
        """, nativeQuery = true)
    boolean upsertNative(
            @Param("id") UUID id,
            @Param("provider") String provider,
            @Param("externalId") String externalId,
            @Param("title") String title,
            @Param("description") String description,
            @Param("type") String type,
            @Param("views") long views,
            @Param("likes") long likes,
            @Param("readingTime") int readingTime,
            @Param("reactions") long reactions,
            @Param("duration") String duration,
            @Param("tags") String[] tags,
            @Param("publishedAt") Instant publishedAt,
            @Param("finalScore") double finalScore,
            @Param("popularityScore") double popularityScore,
            @Param("relevanceScore") double relevanceScore
    );

    /**
     * Full-text search with filtering, sorting, and pagination.
     *
     * <p>Uses {@code plainto_tsquery('simple', :q)} which automatically escapes all
     * FTS metacharacters in user input (REQ 8.5). Sort is determined by the {@code :sort}
     * parameter using CASE expressions. Deterministic tie-break by {@code id ASC} (REQ 9.3–9.6).
     * Pagination via {@code OFFSET/LIMIT} (REQ 20.3).</p>
     *
     * @param q      the search keyword
     * @param type   optional content type filter (null means no filter)
     * @param sort   sort field name: SCORE, POPULARITY, or RELEVANCE
     * @param offset zero-based offset for pagination
     * @param limit  maximum number of results to return
     * @return list of matching content entities
     */
    @Query(value = """
        SELECT c.*
          FROM contents c
         WHERE to_tsvector('simple', c.title || ' ' || coalesce(c.description, '')) @@ plainto_tsquery('simple', :q)
           AND (:type IS NULL OR c.type = :type)
         ORDER BY
           CASE WHEN :sort = 'SCORE'      THEN c.final_score      END DESC,
           CASE WHEN :sort = 'POPULARITY' THEN c.popularity_score END DESC,
           CASE WHEN :sort = 'RELEVANCE'
                THEN ts_rank_cd(
                       to_tsvector('simple', c.title || ' ' || coalesce(c.description, '')),
                       plainto_tsquery('simple', :q))
           END DESC,
           c.id ASC
         OFFSET :offset LIMIT :limit
        """, nativeQuery = true)
    List<ContentEntity> searchFts(
            @Param("q") String q,
            @Param("type") String type,
            @Param("sort") String sort,
            @Param("offset") int offset,
            @Param("limit") int limit
    );

    /**
     * Count query for pagination total, matching the same FTS and type filter
     * as {@link #searchFts(String, String, String, int, int)}.
     *
     * @param q    the search keyword
     * @param type optional content type filter (null means no filter)
     * @return total number of matching rows
     */
    @Query(value = """
        SELECT count(*)
          FROM contents c
         WHERE to_tsvector('simple', c.title || ' ' || coalesce(c.description, '')) @@ plainto_tsquery('simple', :q)
           AND (:type IS NULL OR c.type = :type)
        """, nativeQuery = true)
    long countFts(
            @Param("q") String q,
            @Param("type") String type
    );

    /**
     * Top-N query without a full-text keyword (used by the dashboard for the
     * default "top by score" view, REQ 10.3). Optionally filtered by {@code type},
     * ordered by the supplied {@code sort} value (SCORE / POPULARITY / RELEVANCE)
     * with deterministic tie-break by {@code id ASC}.
     *
     * @param type   optional content type filter (null means no filter)
     * @param sort   sort field name: SCORE, POPULARITY, or RELEVANCE
     * @param limit  maximum number of results to return
     * @return list of matching content entities, at most {@code limit}
     */
    @Query(value = """
        SELECT c.*
          FROM contents c
         WHERE (:type IS NULL OR c.type = :type)
         ORDER BY
           CASE WHEN :sort = 'SCORE'      THEN c.final_score      END DESC,
           CASE WHEN :sort = 'POPULARITY' THEN c.popularity_score END DESC,
           CASE WHEN :sort = 'RELEVANCE'  THEN c.relevance_score  END DESC,
           c.id ASC
         LIMIT :limit
        """, nativeQuery = true)
    List<ContentEntity> listTop(
            @Param("type") String type,
            @Param("sort") String sort,
            @Param("limit") int limit
    );

    /**
     * Count of rows matching the {@code type} filter only (no full-text query),
     * used in conjunction with {@link #listTop(String, String, int)} to populate
     * the dashboard's {@code total} field.
     *
     * @param type optional content type filter (null means no filter)
     * @return total number of matching rows
     */
    @Query(value = """
        SELECT count(*)
          FROM contents c
         WHERE (:type IS NULL OR c.type = :type)
        """, nativeQuery = true)
    long countByType(
            @Param("type") String type
    );
}
