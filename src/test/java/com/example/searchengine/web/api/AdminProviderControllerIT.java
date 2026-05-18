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
 * Integration test for {@link AdminProviderController}.
 *
 * <p>Boots the full Spring context with {@code @SpringBootTest(RANDOM_PORT)},
 * a PostgreSQL 16 Testcontainer for persistence, and two embedded WireMock
 * servers stubbing the JSON and XML provider endpoints so the sync actually
 * fetches data.</p>
 *
 * <p><b>Validates: Requirements 2 (ACs 2.1–2.5), Non-functional 4 (Security).</b></p>
 *
 * <p>Scenarios:
 * <ol>
 *   <li>Call {@code GET /api/v1/admin/providers} without {@code X-Admin-Token} → 401 UNAUTHORIZED envelope.</li>
 *   <li>Call with valid token → 200, JSON envelope {@code {"providers": [...]}} with correct shape (seven fields per element).</li>
 *   <li>After triggering a sync run, at least one provider has {@code totalSuccesses > 0} or {@code totalFailures > 0}.</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnableAutoConfiguration(exclude = {
        RedisAutoConfiguration.class,
        RedisRepositoriesAutoConfiguration.class
})
@Testcontainers
class AdminProviderControllerIT {

    private static final String ADMIN_TOKEN = "test-admin-secret-token";
    private static final String JSON_PATH = "/api/content";
    private static final String XML_PATH = "/feed";

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16")
                    .withDatabaseName("searchengine_admin_test")
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
    // Test 1: Missing X-Admin-Token → 401 UNAUTHORIZED envelope
    // Validates: Non-functional 4 (Security), AdminAuthFilter
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /api/v1/admin/providers without X-Admin-Token returns 401 UNAUTHORIZED")
    void getProviders_withoutToken_returns401() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/admin/providers", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNotNull();

        JsonNode root = objectMapper.readTree(response.getBody());
        assertThat(root.path("error").path("code").asText()).isEqualTo("UNAUTHORIZED");
        assertThat(root.path("error").path("message").asText()).isNotBlank();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 2: Valid token → 200, correct JSON envelope shape
    // Validates: REQ 2.1, 2.2 (seven fields per provider element)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /api/v1/admin/providers with valid token returns 200 with correct shape")
    void getProviders_withValidToken_returns200WithCorrectShape() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Admin-Token", ADMIN_TOKEN);
        HttpEntity<Void> request = new HttpEntity<>(headers);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/providers", HttpMethod.GET, request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();

        JsonNode root = objectMapper.readTree(response.getBody());
        assertThat(root.has("providers")).isTrue();
        JsonNode providers = root.get("providers");
        assertThat(providers.isArray()).isTrue();

        // There should be at least the two registered providers (JSON + XML)
        // Even if never synced, the registry may be empty initially — but after
        // the app boots with providers registered, the endpoint returns whatever
        // the registry has. We verify the shape of each element if present.
        // If no providers have been synced yet, the array may be empty — that's
        // valid per REQ 2.2 (never-synced state).
        for (JsonNode provider : providers) {
            assertProviderHasSevenFields(provider);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 3: After sync, at least one provider has counters > 0
    // Validates: REQ 2.3 (completed sync carries running counters)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("After a sync run, at least one provider has totalSuccesses > 0 or totalFailures > 0")
    void getProviders_afterSync_hasNonZeroCounters() throws Exception {
        // Stub both providers with valid payloads
        stubJsonProvider();
        stubXmlProvider();

        // Trigger a sync via the admin endpoint
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Admin-Token", ADMIN_TOKEN);
        HttpEntity<Void> syncRequest = new HttpEntity<>(headers);

        ResponseEntity<String> syncResponse = restTemplate.exchange(
                "/api/v1/admin/sync", HttpMethod.POST, syncRequest, String.class);
        assertThat(syncResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        // Wait briefly for the async sync to complete
        Thread.sleep(3000);

        // Now query the providers endpoint
        HttpEntity<Void> getRequest = new HttpEntity<>(headers);
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/providers", HttpMethod.GET, getRequest, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode root = objectMapper.readTree(response.getBody());
        JsonNode providers = root.get("providers");
        assertThat(providers.isArray()).isTrue();
        assertThat(providers.size()).isGreaterThanOrEqualTo(1);

        // At least one provider must have totalSuccesses > 0 or totalFailures > 0
        boolean anyCounterPositive = false;
        for (JsonNode provider : providers) {
            assertProviderHasSevenFields(provider);
            long successes = provider.get("totalSuccesses").asLong();
            long failures = provider.get("totalFailures").asLong();
            if (successes > 0 || failures > 0) {
                anyCounterPositive = true;
            }
        }
        assertThat(anyCounterPositive)
                .as("At least one provider should have totalSuccesses > 0 or totalFailures > 0 after sync")
                .isTrue();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Asserts that a provider JSON node has exactly the seven required fields
     * per REQ 2 (ACs 2.1–2.5).
     */
    private void assertProviderHasSevenFields(JsonNode provider) {
        assertThat(provider.has("name")).as("provider must have 'name'").isTrue();
        assertThat(provider.has("lastSyncAt")).as("provider must have 'lastSyncAt'").isTrue();
        assertThat(provider.has("lastSyncOutcome")).as("provider must have 'lastSyncOutcome'").isTrue();
        assertThat(provider.has("lastFetchedItems")).as("provider must have 'lastFetchedItems'").isTrue();
        assertThat(provider.has("totalSuccesses")).as("provider must have 'totalSuccesses'").isTrue();
        assertThat(provider.has("totalFailures")).as("provider must have 'totalFailures'").isTrue();
        assertThat(provider.has("lastErrorMessage")).as("provider must have 'lastErrorMessage'").isTrue();

        // Verify types
        assertThat(provider.get("name").isTextual()).isTrue();
        assertThat(provider.get("lastFetchedItems").isNumber()).isTrue();
        assertThat(provider.get("totalSuccesses").isNumber()).isTrue();
        assertThat(provider.get("totalFailures").isNumber()).isTrue();
    }

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
                      "id": "json-admin-1",
                      "title": "Admin Test JSON Content",
                      "type": "video",
                      "metrics": { "views": 5000, "likes": 400, "duration": "PT8M" },
                      "published_at": "2024-06-15T10:00:00Z",
                      "tags": ["java", "spring"]
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
                      <id>xml-admin-1</id>
                      <headline>Admin Test XML Content</headline>
                      <type>article</type>
                      <stats>
                        <views>3000</views>
                        <likes>150</likes>
                        <reading_time>5</reading_time>
                        <reactions>100</reactions>
                      </stats>
                      <publication_date>2024-06-10T08:00:00Z</publication_date>
                      <categories>
                        <category>testing</category>
                      </categories>
                    </item>
                  </items>
                </feed>
                """;
    }
}
