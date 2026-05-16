package com.example.searchengine.web.error;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ErrorMessageSanitizer} — covers explicit secret lists,
 * environment auto-discovery via the secret-key patterns, and the no-op default.
 *
 * <p>Validates Requirement 18.5.</p>
 */
class ErrorMessageSanitizerTest {

    @Test
    @DisplayName("REDACTION marker is the literal documented in design.md")
    void redactionMarkerMatchesDesignDoc() {
        assertThat(ErrorMessageSanitizer.REDACTION).isEqualTo("***REDACTED***");
    }

    @Test
    @DisplayName("explicit-list constructor: secret values are replaced in messages")
    void explicitListConstructorRedactsConfiguredValues() {
        ErrorMessageSanitizer sanitizer =
                new ErrorMessageSanitizer(List.of("swordfish", "live-api-key"));

        String input = "DB connection failed for user/swordfish (api: live-api-key)";
        String result = sanitizer.sanitize(input);

        assertThat(result).doesNotContain("swordfish");
        assertThat(result).doesNotContain("live-api-key");
        assertThat(result).contains(ErrorMessageSanitizer.REDACTION);
    }

    @Test
    @DisplayName("empty secret list is a no-op")
    void emptySecretListIsNoOp() {
        ErrorMessageSanitizer sanitizer = new ErrorMessageSanitizer(List.of());

        String input = "any message containing swordfish";
        assertThat(sanitizer.sanitize(input)).isEqualTo(input);
    }

    @Test
    @DisplayName("null and empty messages flow through unchanged")
    void nullAndEmptyMessagesPassThrough() {
        ErrorMessageSanitizer sanitizer =
                new ErrorMessageSanitizer(List.of("swordfish"));

        assertThat(sanitizer.sanitize(null)).isNull();
        assertThat(sanitizer.sanitize("")).isEqualTo("");
    }

    @Test
    @DisplayName("environment auto-discovery picks up secret-keyed property values")
    void environmentAutoDiscoveryPicksUpSecretKeyedValues() {
        MockEnvironment env = new MockEnvironment();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("spring.datasource.password", "swordfish");
        map.put("spring.datasource.url", "jdbc:postgresql://db:5432/x");
        map.put("providers.json.api-key", "live-api-key");
        env.getPropertySources().addLast(new MapPropertySource("test", map));

        ErrorMessageSanitizer sanitizer = new ErrorMessageSanitizer(new String[0], env);

        String message = "Failed: password=swordfish url=jdbc:postgresql://db:5432/x key=live-api-key";
        String result = sanitizer.sanitize(message);

        assertThat(result).doesNotContain("swordfish");
        assertThat(result).doesNotContain("live-api-key");
        // Non-secret values (URL) are preserved verbatim.
        assertThat(result).contains("jdbc:postgresql://db:5432/x");
        assertThat(result).contains(ErrorMessageSanitizer.REDACTION);
    }

    @Test
    @DisplayName("environment auto-discovery is combined with explicit secret.values")
    void explicitListAndEnvironmentDiscoveryAreCombined() {
        MockEnvironment env = new MockEnvironment();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("spring.datasource.password", "swordfish");
        env.getPropertySources().addLast(new MapPropertySource("test", map));

        ErrorMessageSanitizer sanitizer =
                new ErrorMessageSanitizer(new String[]{"explicit-token"}, env);

        String message = "DB password=swordfish; legacy=explicit-token";
        String result = sanitizer.sanitize(message);

        assertThat(result).doesNotContain("swordfish");
        assertThat(result).doesNotContain("explicit-token");
        assertThat(result).contains(ErrorMessageSanitizer.REDACTION);
    }

    @Test
    @DisplayName("environment with no secret-keyed properties yields a no-op sanitizer")
    void environmentWithoutSecretKeyedPropertiesIsNoOp() {
        MockEnvironment env = new MockEnvironment();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("server.port", "8080");
        map.put("spring.application.name", "searchengine");
        env.getPropertySources().addLast(new MapPropertySource("test", map));

        ErrorMessageSanitizer sanitizer = new ErrorMessageSanitizer(new String[0], env);

        String message = "starting on port 8080 as searchengine";
        assertThat(sanitizer.sanitize(message)).isEqualTo(message);
    }

    @Test
    @DisplayName("secret values are deduplicated across explicit list and environment")
    void secretValuesAreDeduplicated() {
        MockEnvironment env = new MockEnvironment();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("spring.datasource.password", "shared-secret");
        env.getPropertySources().addLast(new MapPropertySource("test", map));

        ErrorMessageSanitizer sanitizer =
                new ErrorMessageSanitizer(new String[]{"shared-secret", "shared-secret"}, env);

        // Single occurrence in the secrets list (no duplicates).
        assertThat(sanitizer.secrets()).containsExactly("shared-secret");
    }
}
