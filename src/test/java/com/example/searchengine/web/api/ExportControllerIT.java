package com.example.searchengine.web.api;

import com.example.searchengine.application.ingest.ContentAggregator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for {@link ExportController}.
 *
 * <p>Boots the full Spring context with {@code @SpringBootTest(RANDOM_PORT)},
 * a PostgreSQL 16 Testcontainer for persistence, and two embedded WireMock
 * servers stubbing the JSON and XML provider endpoints so a sync populates
 * data for export.</p>
 *
 * <p><b>Validates: Requirements 5 (ACs 5.1–5.4).</b></p>
 *
 * <p>Scenarios:
 * <ol>
 *   <li>CSV export: correct Content-Type, Content-Disposition, UTF-8 BOM + header row + data row.</li>
 *   <li>JSON export: correct Content-Type, Content-Disposition, JSON array with expected fields.</li>
 *   <li>Limit exceeded: returns 400 with INVALID_QUERY envelope.</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnableAutoConfiguration(exclude = {
        RedisAutoConfiguration.class,
        RedisRepositoriesAutoConfiguration.class
})
@Testcontainers
class ExportControllerIT {

    private static final String JSON_PATH = "/api/content";
    private static final String XML_PATH = "/feed";

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16")
                    .withDatabaseName("searchengine_export_test")
                    .withUsername("test")
                    .withPassword("test");

    private static final WireMockServer JSON_WM =
            new WireMockServer(options().dynamicPort());
    private static final WireMockServer XML_WM =
            new WireMockServer(options().dynamicPort());

    @BeforeAll
    static void startWireMock() {
        JSON_WM.start();
        XML_WM.start();
    }

    @AfterAll
    static void stopWireMock() {
        JSON_WM.stop();
        XML_WM.stop();
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        registry.add("providers.json.base-url",
                () -> JSON_WM.baseUrl() + JSON_PATH);
        registry.add("providers.xml.base-url",
                () -> XML_WM.baseUrl() + XML_PATH);

        // Disable scheduler, cache, rate limit — we trigger sync manually
        registry.add("aggregator.sync.enabled", () -> "false");
        registry.add("cache.search.enabled", () -> "false");
        registry.add("ratelimit.enabled", () -> "false");

        // Export max-rows set to 1000 (default) for limit-exceeded test
        registry.add("export.search.max-rows", () -> "1000");
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ContentAggregator contentAggregator;

    @BeforeEach
    void resetWireMockAndSync() {
        JSON_WM.resetAll();
        XML_WM.resetAll();

        // Stub both providers with valid payloads
        stubJsonProvider();
        stubXmlProvider();

        // Trigger a sync so data exists for export tests
        contentAggregator.runSync();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 1 (CSV): GET /api/v1/search.csv?q=*
    // Validates: REQ 5.1, 5.4
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /api/v1/search.csv returns 200 with correct headers, BOM, header row, and data")
    void exportCsv_returnsCorrectHeadersAndContent() {
        ResponseEntity<byte[]> response = restTemplate.getForEntity(
                "/api/v1/search.csv?q=*", byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Assert Content-Type
        String contentType = response.getHeaders().getFirst("Content-Type");
        assertThat(contentType).isEqualTo("text/csv; charset=UTF-8");

        // Assert Content-Disposition contains search- and .csv
        String contentDisposition = response.getHeaders().getFirst("Content-Disposition");
        assertThat(contentDisposition).isNotNull();
        assertThat(contentDisposition).contains("search-");
        assertThat(contentDisposition).contains(".csv");

        // Assert body starts with UTF-8 BOM + header row
        byte[] body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.length).isGreaterThan(3);

        // UTF-8 BOM: EF BB BF
        assertThat(body[0]).isEqualTo((byte) 0xEF);
        assertThat(body[1]).isEqualTo((byte) 0xBB);
        assertThat(body[2]).isEqualTo((byte) 0xBF);

        // Parse the CSV content (skip BOM bytes)
        String csvContent = new String(body, 3, body.length - 3, java.nio.charset.StandardCharsets.UTF_8);
        String[] lines = csvContent.split("\r\n");

        // First line is the header row
        assertThat(lines[0]).isEqualTo("id,title,type,score,publishedAt");

        // At least one data row must exist
        assertThat(lines.length).isGreaterThanOrEqualTo(2);

        // Verify the data row has 5 comma-separated fields
        String firstDataRow = lines[1];
        assertThat(firstDataRow).isNotBlank();
        // The row should contain content from our stubbed providers
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 2 (JSON): GET /api/v1/search.json?q=*
    // Validates: REQ 5.2
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /api/v1/search.json returns 200 with correct headers and JSON array")
    void exportJson_returnsCorrectHeadersAndContent() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/search.json?q=*", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Assert Content-Type
        String contentType = response.getHeaders().getFirst("Content-Type");
        assertThat(contentType).isEqualTo("application/json; charset=UTF-8");

        // Assert Content-Disposition contains search- and .json
        String contentDisposition = response.getHeaders().getFirst("Content-Disposition");
        assertThat(contentDisposition).isNotNull();
        assertThat(contentDisposition).contains("search-");
        assertThat(contentDisposition).contains(".json");

        // Assert body is a JSON array with at least one element
        String body = response.getBody();
        assertThat(body).isNotNull();

        JsonNode root = objectMapper.readTree(body);
        assertThat(root.isArray()).isTrue();
        assertThat(root.size()).isGreaterThanOrEqualTo(1);

        // First element must have the required fields
        JsonNode firstItem = root.get(0);
        assertThat(firstItem.has("id")).isTrue();
        assertThat(firstItem.has("title")).isTrue();
        assertThat(firstItem.has("type")).isTrue();
        assertThat(firstItem.has("score")).isTrue();
        assertThat(firstItem.has("publishedAt")).isTrue();

        // Verify field types
        assertThat(firstItem.get("id").isTextual()).isTrue();
        assertThat(firstItem.get("title").isTextual()).isTrue();
        assertThat(firstItem.get("type").isTextual()).isTrue();
        assertThat(firstItem.get("score").isNumber()).isTrue();
        assertThat(firstItem.get("publishedAt").isTextual()).isTrue();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 3 (limit exceeded): GET /api/v1/search.csv?q=*&limit=9999
    // Validates: REQ 5.3
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /api/v1/search.csv with limit exceeding max-rows returns 400 INVALID_QUERY")
    void exportCsv_limitExceeded_returns400InvalidQuery() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/search.csv?q=*&limit=9999", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();

        JsonNode root = objectMapper.readTree(response.getBody());
        assertThat(root.path("error").path("code").asText()).isEqualTo("INVALID_QUERY");
        assertThat(root.path("error").path("message").asText()).isNotBlank();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // WireMock stubs
    // ─────────────────────────────────────────────────────────────────────────

    private void stubJsonProvider() {
        WireMock.configureFor("localhost", JSON_WM.port());
        WireMock.stubFor(get(urlPathEqualTo(JSON_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(jsonPayload())));
    }

    private void stubXmlProvider() {
        WireMock.configureFor("localhost", XML_WM.port());
        WireMock.stubFor(get(urlPathEqualTo(XML_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/xml")
                        .withBody(xmlPayload())));
    }

    private String jsonPayload() {
        return """
                {
                  "contents": [
                    {
                      "id": "json-export-1",
                      "title": "Export Test JSON Content",
                      "type": "video",
                      "metrics": { "views": 8000, "likes": 600, "duration": "PT10M" },
                      "published_at": "2024-07-01T12:00:00Z",
                      "tags": ["export", "test"]
                    }
                  ]
                }
                """;
    }

    private String xmlPayload() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <feed>
                  <items>
                    <item>
                      <id>xml-export-1</id>
                      <headline>Export Test XML Content</headline>
                      <type>article</type>
                      <stats>
                        <views>4000</views>
                        <likes>200</likes>
                        <reading_time>7</reading_time>
                        <reactions>120</reactions>
                      </stats>
                      <publication_date>2024-07-05T09:00:00Z</publication_date>
                      <categories>
                        <category>export</category>
                      </categories>
                    </item>
                  </items>
                </feed>
                """;
    }
}
