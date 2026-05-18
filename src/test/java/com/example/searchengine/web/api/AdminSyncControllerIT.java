package com.example.searchengine.web.api;

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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
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
 * Integration test for {@link AdminSyncController}.
 *
 * <p>Boots the full Spring context with {@code @SpringBootTest(RANDOM_PORT)},
 * a PostgreSQL 16 Testcontainer for persistence, and two embedded WireMock
 * servers stubbing the JSON and XML provider endpoints.</p>
 *
 * <p>The JSON provider is stubbed with a <b>delayed response</b> (4 seconds) so
 * the sync takes long enough to observe the "already running" state when a
 * second request arrives.</p>
 *
 * <p><b>Validates: Requirements 3 (ACs 3.1, 3.2).</b></p>
 *
 * <p>Scenarios:
 * <ol>
 *   <li>{@code POST /api/v1/admin/sync} with valid token → 202,
 *       {@code {"triggered": true, "alreadyRunning": false}}.</li>
 *   <li>While the first sync is still running (WireMock delay keeps it busy),
 *       a second {@code POST /api/v1/admin/sync} → 202,
 *       {@code {"triggered": false, "alreadyRunning": true}}.</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnableAutoConfiguration(exclude = {
        RedisAutoConfiguration.class,
        RedisRepositoriesAutoConfiguration.class
})
@Testcontainers
class AdminSyncControllerIT {

    private static final String ADMIN_TOKEN = "test-sync-admin-token";
    private static final String JSON_PATH = "/api/content";
    private static final String XML_PATH = "/feed";

    /** Delay in milliseconds applied to the JSON provider stub to keep sync busy. */
    private static final int PROVIDER_DELAY_MS = 4000;

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16")
                    .withDatabaseName("searchengine_sync_test")
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

        // Disable scheduler — we trigger sync manually via the admin endpoint
        registry.add("aggregator.sync.enabled", () -> "false");
        registry.add("cache.search.enabled", () -> "false");
        registry.add("ratelimit.enabled", () -> "false");

        // Set the admin auth token
        registry.add("admin.auth.token", () -> ADMIN_TOKEN);

        // Increase read timeout so the delayed WireMock response doesn't time out
        registry.add("providers.json.read-timeout-ms", () -> "15000");
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void resetWireMock() {
        JSON_WM.resetAll();
        XML_WM.resetAll();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 1: First POST triggers sync → 202, triggered=true, alreadyRunning=false
    // Validates: REQ 3.1
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /api/v1/admin/sync with valid token triggers sync → 202 triggered=true")
    void triggerSync_firstCall_returns202Triggered() throws Exception {
        // Stub providers with fast responses for this test
        stubJsonProviderFast();
        stubXmlProvider();

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Admin-Token", ADMIN_TOKEN);
        HttpEntity<Void> request = new HttpEntity<>(headers);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/sync", HttpMethod.POST, request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isNotNull();

        JsonNode root = objectMapper.readTree(response.getBody());
        assertThat(root.get("triggered").asBoolean()).isTrue();
        assertThat(root.get("alreadyRunning").asBoolean()).isFalse();

        // Wait for the sync to complete before the next test
        Thread.sleep(2000);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 2: Second POST while first is running → 202, alreadyRunning=true
    // Validates: REQ 3.2
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /api/v1/admin/sync while sync is running → 202 alreadyRunning=true")
    void triggerSync_whileRunning_returns202AlreadyRunning() throws Exception {
        // Stub JSON provider with a DELAYED response so the sync takes long enough
        stubJsonProviderDelayed();
        stubXmlProvider();

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Admin-Token", ADMIN_TOKEN);
        HttpEntity<Void> request = new HttpEntity<>(headers);

        // First call: triggers the sync
        ResponseEntity<String> firstResponse = restTemplate.exchange(
                "/api/v1/admin/sync", HttpMethod.POST, request, String.class);

        assertThat(firstResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        JsonNode firstBody = objectMapper.readTree(firstResponse.getBody());
        assertThat(firstBody.get("triggered").asBoolean()).isTrue();
        assertThat(firstBody.get("alreadyRunning").asBoolean()).isFalse();

        // Brief pause to let the executor pick up the task and start running
        Thread.sleep(500);

        // Second call: should observe the sync is already running
        ResponseEntity<String> secondResponse = restTemplate.exchange(
                "/api/v1/admin/sync", HttpMethod.POST, request, String.class);

        assertThat(secondResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(secondResponse.getBody()).isNotNull();

        JsonNode secondBody = objectMapper.readTree(secondResponse.getBody());
        assertThat(secondBody.get("triggered").asBoolean()).isFalse();
        assertThat(secondBody.get("alreadyRunning").asBoolean()).isTrue();

        // Wait for the delayed sync to complete so it doesn't interfere with other tests
        Thread.sleep(PROVIDER_DELAY_MS + 1000);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Stubs the JSON provider with a fast response (no delay). */
    private void stubJsonProviderFast() {
        WireMock.configureFor("localhost", JSON_WM.port());
        WireMock.stubFor(get(urlPathEqualTo(JSON_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(jsonPayload())));
    }

    /** Stubs the JSON provider with a delayed response to keep the sync busy. */
    private void stubJsonProviderDelayed() {
        WireMock.configureFor("localhost", JSON_WM.port());
        WireMock.stubFor(get(urlPathEqualTo(JSON_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withFixedDelay(PROVIDER_DELAY_MS)
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
                      "id": "json-sync-1",
                      "title": "Sync Test JSON Content",
                      "type": "video",
                      "metrics": { "views": 1000, "likes": 100, "duration": "PT5M" },
                      "published_at": "2024-07-01T12:00:00Z",
                      "tags": ["test"]
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
                      <id>xml-sync-1</id>
                      <headline>Sync Test XML Content</headline>
                      <type>article</type>
                      <stats>
                        <views>2000</views>
                        <likes>80</likes>
                        <reading_time>3</reading_time>
                        <reactions>50</reactions>
                      </stats>
                      <publication_date>2024-07-02T09:00:00Z</publication_date>
                      <categories>
                        <category>testing</category>
                      </categories>
                    </item>
                  </items>
                </feed>
                """;
    }
}
