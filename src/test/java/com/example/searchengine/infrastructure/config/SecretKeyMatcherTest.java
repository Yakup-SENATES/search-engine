package com.example.searchengine.infrastructure.config;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SecretKeyMatcher} — verifies the default secret key
 * pattern set, case-insensitive matching, and the {@link SecretKeyMatcher#redactValue(String, String)}
 * helper.
 *
 * <p>Validates Requirement 18.5.</p>
 */
class SecretKeyMatcherTest {

    @ParameterizedTest(name = "key=''{0}'' is recognized as secret")
    @ValueSource(strings = {
            "spring.datasource.password",
            "PROVIDER_API_KEY",
            "providers.json.api-key",
            "auth.token",
            "X_AUTH_TOKEN",
            "providers.xml.apiKey",      // contains 'api'+'key' merged → 'apikey' substring
            "client.secret",
            "DB_PASSWORD",
            "credentials.username"        // note: 'credential' substring matches
    })
    @DisplayName("default patterns recognize common secret-keyed property names")
    void defaultPatternsMatchCommonSecretKeys(String key) {
        SecretKeyMatcher matcher = new SecretKeyMatcher();
        assertThat(matcher.isSecret(key))
                .as("key %s should be classified as secret", key)
                .isTrue();
    }

    @ParameterizedTest(name = "key=''{0}'' is NOT recognized as secret")
    @ValueSource(strings = {
            "spring.datasource.url",
            "spring.datasource.username",
            "providers.json.base-url",
            "server.port",
            "cache.search.ttl-seconds",
            "ratelimit.requests-per-window"
    })
    @DisplayName("non-secret keys are not classified as secret")
    void nonSecretKeysAreNotClassifiedAsSecret(String key) {
        SecretKeyMatcher matcher = new SecretKeyMatcher();
        assertThat(matcher.isSecret(key)).isFalse();
    }

    @Test
    @DisplayName("null and empty keys are not secrets")
    void nullAndEmptyKeysAreNotSecrets() {
        SecretKeyMatcher matcher = new SecretKeyMatcher();
        assertThat(matcher.isSecret(null)).isFalse();
        assertThat(matcher.isSecret("")).isFalse();
    }

    @Test
    @DisplayName("redactValue replaces secret values and passes through non-secret values")
    void redactValueRedactsSecretValuesAndPassesThroughOthers() {
        SecretKeyMatcher matcher = new SecretKeyMatcher();
        assertThat(matcher.redactValue("spring.datasource.password", "swordfish"))
                .isEqualTo(SecretKeyMatcher.REDACTION);
        assertThat(matcher.redactValue("server.port", "8080"))
                .isEqualTo("8080");
        assertThat(matcher.redactValue("server.port", null))
                .isNull();
    }

    @Test
    @DisplayName("custom pattern list overrides the default set")
    void customPatternListOverridesDefaults() {
        SecretKeyMatcher matcher = new SecretKeyMatcher(List.of("custom"));
        assertThat(matcher.isSecret("my.custom.key")).isTrue();
        // 'password' is NOT in the custom list, so this is no longer secret.
        assertThat(matcher.isSecret("spring.datasource.password")).isFalse();
    }

    @Test
    @DisplayName("blank entries in the pattern list are dropped")
    void blankPatternsAreDropped() {
        SecretKeyMatcher matcher = new SecretKeyMatcher(List.of("", "  ", "secret"));
        assertThat(matcher.patterns()).containsExactly("secret");
    }

    @Test
    @DisplayName("REDACTION marker matches the value documented in design.md")
    void redactionMarkerMatchesDesignDocLiteral() {
        assertThat(SecretKeyMatcher.REDACTION).isEqualTo("***REDACTED***");
    }
}
