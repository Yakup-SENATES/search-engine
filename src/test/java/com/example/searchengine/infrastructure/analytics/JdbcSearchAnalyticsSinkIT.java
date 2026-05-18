package com.example.searchengine.infrastructure.analytics;

import com.example.searchengine.application.analytics.SearchAnalyticsRecord;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for {@link JdbcSearchAnalyticsSink} against a real
 * Testcontainers Postgres instance with Flyway migrations applied.
 *
 * <p>Verifies that records are correctly persisted to the
 * {@code search_analytics} table.</p>
 */
@Testcontainers
class JdbcSearchAnalyticsSinkIT {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16")
                    .withDatabaseName("testdb")
                    .withUsername("test")
                    .withPassword("test");

    private JdbcTemplate jdbcTemplate;
    private JdbcSearchAnalyticsSink sink;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setUrl(POSTGRES.getJdbcUrl());
        ds.setUsername(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());

        // Apply Flyway migrations (V1 + V2)
        Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        jdbcTemplate = new JdbcTemplate(ds);
        sink = new JdbcSearchAnalyticsSink(jdbcTemplate);

        // Clean table before each test
        jdbcTemplate.execute("DELETE FROM search_analytics");
    }

    @Test
    @DisplayName("record() persists a complete record to search_analytics table")
    void recordPersistsCompleteRecord() {
        Instant requestedAt = Instant.parse("2024-06-15T10:30:00Z");
        SearchAnalyticsRecord record = new SearchAnalyticsRecord(
                requestedAt,
                "spring boot",
                "TEXT",
                "score",
                1,
                10,
                42L,
                15,
                false,
                "req-abc-123",
                "a".repeat(64),
                null
        );

        sink.record(record);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT * FROM search_analytics WHERE request_id = ?", "req-abc-123");

        assertThat(row.get("q")).isEqualTo("spring boot");
        assertThat(row.get("type")).isEqualTo("TEXT");
        assertThat(row.get("sort")).isEqualTo("score");
        assertThat(row.get("page")).isEqualTo(1);
        assertThat(row.get("limit")).isEqualTo(10);
        assertThat(row.get("total_results")).isEqualTo(42L);
        assertThat(row.get("latency_ms")).isEqualTo(15);
        assertThat(row.get("cache_hit")).isEqualTo(false);
        assertThat(row.get("request_id")).isEqualTo("req-abc-123");
        assertThat(row.get("client_ip_hash")).hasToString("a".repeat(64));
        assertThat(row.get("error_code")).isNull();
    }

    @Test
    @DisplayName("record() persists a record with null optional fields")
    void recordPersistsWithNullOptionalFields() {
        Instant requestedAt = Instant.parse("2024-01-01T12:00:00Z");
        SearchAnalyticsRecord record = new SearchAnalyticsRecord(
                requestedAt,
                "test query",
                null,
                "date",
                3,
                20,
                null,
                8,
                true,
                "req-null-test",
                null,
                "INTERNAL_ERROR"
        );

        sink.record(record);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT * FROM search_analytics WHERE request_id = ?", "req-null-test");

        assertThat(row.get("q")).isEqualTo("test query");
        assertThat(row.get("type")).isNull();
        assertThat(row.get("sort")).isEqualTo("date");
        assertThat(row.get("page")).isEqualTo(3);
        assertThat(row.get("limit")).isEqualTo(20);
        assertThat(row.get("total_results")).isNull();
        assertThat(row.get("latency_ms")).isEqualTo(8);
        assertThat(row.get("cache_hit")).isEqualTo(true);
        assertThat(row.get("client_ip_hash")).isNull();
        assertThat(row.get("error_code")).isEqualTo("INTERNAL_ERROR");
    }

    @Test
    @DisplayName("record() persists multiple records independently")
    void recordPersistsMultipleRecords() {
        SearchAnalyticsRecord record1 = new SearchAnalyticsRecord(
                Instant.now(),
                "first query",
                null,
                "score",
                1,
                10,
                5L,
                12,
                false,
                "req-multi-1",
                "d".repeat(64),
                null
        );
        SearchAnalyticsRecord record2 = new SearchAnalyticsRecord(
                Instant.now(),
                "second query",
                "VIDEO",
                "date",
                2,
                25,
                99L,
                45,
                true,
                "req-multi-2",
                "e".repeat(64),
                null
        );

        sink.record(record1);
        sink.record(record2);

        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM search_analytics", Long.class);
        assertThat(count).isEqualTo(2);
    }
}
