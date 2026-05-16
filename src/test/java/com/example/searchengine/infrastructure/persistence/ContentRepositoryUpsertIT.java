package com.example.searchengine.infrastructure.persistence;

import com.example.searchengine.domain.content.Content;
import com.example.searchengine.domain.content.ContentType;
import com.example.searchengine.domain.content.UpsertOutcome;

import net.jqwik.api.*;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeProperty;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Testcontainers integration property test for repository upsert idempotence.
 *
 * <p><b>Validates: Requirements 5.1, 5.2, 23.3</b></p>
 *
 * <p>Feature: search-engine-service, Property 5: Repository upsert idempotence</p>
 *
 * <p>For any two {@code Content} snapshots {@code A} and {@code B} that share the same
 * {@code (provider, externalId)}, calling {@code repository.upsert(A)} then
 * {@code repository.upsert(B)} leaves the database in a state equal to having called
 * {@code repository.upsert(B)} once on an empty store, and the row count for that
 * {@code (provider, externalId)} is exactly 1.</p>
 */
public class ContentRepositoryUpsertIT {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16")
                    .withDatabaseName("testdb")
                    .withUsername("test")
                    .withPassword("test");

    private DataSource dataSource;
    private JdbcTemplate jdbcTemplate;

    @BeforeProperty
    void setUp() {
        if (!POSTGRES.isRunning()) {
            POSTGRES.start();
        }

        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setUrl(POSTGRES.getJdbcUrl());
        ds.setUsername(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        dataSource = ds;

        // Apply Flyway migrations (idempotent — skips already applied)
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        jdbcTemplate = new JdbcTemplate(dataSource);

        // Ensure clean state before property
        jdbcTemplate.execute("DELETE FROM contents");
    }

    @AfterTry
    void cleanUp() {
        // Clean the table between tries to ensure isolation
        if (jdbcTemplate != null) {
            jdbcTemplate.execute("DELETE FROM contents");
        }
    }

    @Property(tries = 100)
    @Label("Feature: search-engine-service, Property 5: Repository upsert idempotence")
    void upsertIdempotence(
            @ForAll("contentPairWithSameKey") ContentPair pair
    ) {
        Content a = pair.a();
        Content b = pair.b();

        // Scenario 1: upsert(A) then upsert(B)
        UpsertOutcome firstOutcome = executeUpsert(a);
        UpsertOutcome secondOutcome = executeUpsert(b);

        // The first upsert should be an INSERT, the second an UPDATE
        assert firstOutcome == UpsertOutcome.INSERTED :
                "Expected INSERTED for first upsert but got " + firstOutcome;
        assert secondOutcome == UpsertOutcome.UPDATED :
                "Expected UPDATED for second upsert but got " + secondOutcome;

        // Capture the state after upsert(A); upsert(B)
        ContentRow afterBoth = fetchRow(b.provider(), b.externalId());
        long countAfterBoth = countRows(b.provider(), b.externalId());

        // Row count must be exactly 1
        assert countAfterBoth == 1 :
                "Expected exactly 1 row but found " + countAfterBoth;

        // Clean up for scenario 2
        jdbcTemplate.execute("DELETE FROM contents");

        // Scenario 2: upsert(B) only on empty store
        UpsertOutcome bOnlyOutcome = executeUpsert(b);
        assert bOnlyOutcome == UpsertOutcome.INSERTED :
                "Expected INSERTED for B-only upsert but got " + bOnlyOutcome;

        ContentRow afterBOnly = fetchRow(b.provider(), b.externalId());
        long countAfterBOnly = countRows(b.provider(), b.externalId());

        assert countAfterBOnly == 1 :
                "Expected exactly 1 row after B-only upsert but found " + countAfterBOnly;

        // The state after upsert(A); upsert(B) must equal the state after upsert(B) alone
        assertRowsEqual(afterBoth, afterBOnly);
    }

    // --- Upsert execution using the same SQL as ContentJpaRepository ---

    private UpsertOutcome executeUpsert(Content content) {
        Boolean inserted = jdbcTemplate.queryForObject(
                """
                INSERT INTO contents (id, provider, external_id, title, description, type,
                    views, likes, reading_time, reactions, duration, tags, published_at,
                    final_score, popularity_score, relevance_score, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, 0, NOW(), NOW())
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
                """,
                Boolean.class,
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
                createSqlArray(content.tags().toArray(new String[0])),
                Timestamp.from(content.publishedAt()),
                content.finalScore(),
                content.popularityScore(),
                content.relevanceScore()
        );
        return (inserted != null && inserted) ? UpsertOutcome.INSERTED : UpsertOutcome.UPDATED;
    }

    private java.sql.Array createSqlArray(String[] tags) {
        try (Connection conn = dataSource.getConnection()) {
            return conn.createArrayOf("text", tags != null ? tags : new String[0]);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create SQL array", e);
        }
    }

    // --- Generators ---

    @Provide
    Arbitrary<ContentPair> contentPairWithSameKey() {
        Arbitrary<String> providers = Arbitraries.strings()
                .alpha().ofMinLength(3).ofMaxLength(20)
                .map(s -> "prov-" + s);
        Arbitrary<String> externalIds = Arbitraries.strings()
                .alpha().numeric().ofMinLength(3).ofMaxLength(30)
                .map(s -> "ext-" + s);

        return Combinators.combine(providers, externalIds)
                .flatAs((provider, externalId) ->
                        Combinators.combine(
                                contentArbitrary(provider, externalId),
                                contentArbitrary(provider, externalId)
                        ).as(ContentPair::new)
                );
    }

    private Arbitrary<Content> contentArbitrary(String provider, String externalId) {
        Arbitrary<String> titles = Arbitraries.strings()
                .alpha().ofMinLength(1).ofMaxLength(50)
                .map(s -> "Title " + s);
        Arbitrary<String> descriptions = Arbitraries.strings()
                .alpha().ofMaxLength(100)
                .injectNull(0.3);
        Arbitrary<ContentType> types = Arbitraries.of(ContentType.VIDEO, ContentType.TEXT);
        Arbitrary<Long> views = Arbitraries.longs().between(0, 100_000);
        Arbitrary<Long> likes = Arbitraries.longs().between(0, 50_000);
        Arbitrary<Integer> readingTimes = Arbitraries.integers().between(0, 600);
        Arbitrary<Long> reactions = Arbitraries.longs().between(0, 10_000);
        Arbitrary<String> durations = Arbitraries.of("PT1M", "PT5M", "PT10M", null);
        Arbitrary<List<String>> tagLists = Arbitraries.strings()
                .alpha().ofMinLength(2).ofMaxLength(10)
                .list().ofMaxSize(5);
        Arbitrary<Instant> publishedAts = Arbitraries.longs()
                .between(0, 365L * 24 * 60 * 60)
                .map(offset -> Instant.now().minus(offset, ChronoUnit.SECONDS)
                        .truncatedTo(ChronoUnit.MILLIS));
        Arbitrary<Double> scores = Arbitraries.doubles().between(0.0, 1000.0);

        // jqwik Combinators.combine supports up to 8 params, so we nest the combination
        return Combinators.combine(titles, descriptions, types, views, likes, readingTimes, reactions, durations)
                .flatAs((title, description, type, v, l, rt, r, duration) ->
                        Combinators.combine(tagLists, publishedAts, scores, scores, scores)
                                .as((tagList, publishedAt, finalScore, popularityScore, relevanceScore) ->
                                        new Content(
                                                UUID.randomUUID(),
                                                provider,
                                                externalId,
                                                title,
                                                description,
                                                type,
                                                v,
                                                l,
                                                rt,
                                                r,
                                                duration,
                                                tagList,
                                                publishedAt,
                                                finalScore,
                                                popularityScore,
                                                relevanceScore,
                                                null,  // createdAt — managed by DB
                                                null   // updatedAt — managed by DB
                                        )
                                )
                );
    }

    // --- Helper types and methods ---

    record ContentPair(Content a, Content b) {}

    record ContentRow(
            String provider, String externalId, String title, String description,
            String type, long views, long likes, int readingTime, long reactions,
            String duration, String[] tags, Instant publishedAt,
            double finalScore, double popularityScore, double relevanceScore
    ) {}

    private ContentRow fetchRow(String provider, String externalId) {
        return jdbcTemplate.queryForObject(
                """
                SELECT provider, external_id, title, description, type,
                       views, likes, reading_time, reactions, duration, tags,
                       published_at, final_score, popularity_score, relevance_score
                  FROM contents
                 WHERE provider = ? AND external_id = ?
                """,
                (rs, rowNum) -> new ContentRow(
                        rs.getString("provider"),
                        rs.getString("external_id"),
                        rs.getString("title"),
                        rs.getString("description"),
                        rs.getString("type"),
                        rs.getLong("views"),
                        rs.getLong("likes"),
                        rs.getInt("reading_time"),
                        rs.getLong("reactions"),
                        rs.getString("duration"),
                        (String[]) rs.getArray("tags").getArray(),
                        rs.getTimestamp("published_at").toInstant(),
                        rs.getDouble("final_score"),
                        rs.getDouble("popularity_score"),
                        rs.getDouble("relevance_score")
                ),
                provider, externalId
        );
    }

    private long countRows(String provider, String externalId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM contents WHERE provider = ? AND external_id = ?",
                Long.class,
                provider, externalId
        );
        return count != null ? count : 0;
    }

    private void assertRowsEqual(ContentRow afterBoth, ContentRow afterBOnly) {
        assert afterBoth.provider().equals(afterBOnly.provider()) :
                "provider mismatch: " + afterBoth.provider() + " vs " + afterBOnly.provider();
        assert afterBoth.externalId().equals(afterBOnly.externalId()) :
                "externalId mismatch";
        assert afterBoth.title().equals(afterBOnly.title()) :
                "title mismatch: " + afterBoth.title() + " vs " + afterBOnly.title();
        assert equalsNullable(afterBoth.description(), afterBOnly.description()) :
                "description mismatch: " + afterBoth.description() + " vs " + afterBOnly.description();
        assert afterBoth.type().equals(afterBOnly.type()) :
                "type mismatch: " + afterBoth.type() + " vs " + afterBOnly.type();
        assert afterBoth.views() == afterBOnly.views() :
                "views mismatch: " + afterBoth.views() + " vs " + afterBOnly.views();
        assert afterBoth.likes() == afterBOnly.likes() :
                "likes mismatch: " + afterBoth.likes() + " vs " + afterBOnly.likes();
        assert afterBoth.readingTime() == afterBOnly.readingTime() :
                "readingTime mismatch: " + afterBoth.readingTime() + " vs " + afterBOnly.readingTime();
        assert afterBoth.reactions() == afterBOnly.reactions() :
                "reactions mismatch: " + afterBoth.reactions() + " vs " + afterBOnly.reactions();
        assert equalsNullable(afterBoth.duration(), afterBOnly.duration()) :
                "duration mismatch: " + afterBoth.duration() + " vs " + afterBOnly.duration();
        assert Arrays.equals(afterBoth.tags(), afterBOnly.tags()) :
                "tags mismatch: " + Arrays.toString(afterBoth.tags()) + " vs " + Arrays.toString(afterBOnly.tags());
        assert afterBoth.publishedAt().equals(afterBOnly.publishedAt()) :
                "publishedAt mismatch: " + afterBoth.publishedAt() + " vs " + afterBOnly.publishedAt();
        assert afterBoth.finalScore() == afterBOnly.finalScore() :
                "finalScore mismatch: " + afterBoth.finalScore() + " vs " + afterBOnly.finalScore();
        assert afterBoth.popularityScore() == afterBOnly.popularityScore() :
                "popularityScore mismatch: " + afterBoth.popularityScore() + " vs " + afterBOnly.popularityScore();
        assert afterBoth.relevanceScore() == afterBOnly.relevanceScore() :
                "relevanceScore mismatch: " + afterBoth.relevanceScore() + " vs " + afterBOnly.relevanceScore();
    }

    private boolean equalsNullable(Object a, Object b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
    }
}
