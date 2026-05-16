package com.example.searchengine.infrastructure.config;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SecretRedactingPropertySource} — verifies that secret
 * keys read through the wrapper return the redaction marker, that non-secret
 * keys pass through unchanged, that absent keys return {@code null}, and that
 * property names continue to be enumerable.
 *
 * <p>Validates Requirement 18.5.</p>
 */
class SecretRedactingPropertySourceTest {

    private static MapPropertySource sampleSource() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("spring.datasource.url", "jdbc:postgresql://db:5432/searchengine");
        map.put("spring.datasource.username", "appuser");
        map.put("spring.datasource.password", "swordfish");
        map.put("providers.json.api-key", "live-key-abc123");
        map.put("providers.json.base-url", "https://json.example.com");
        return new MapPropertySource("sample", map);
    }

    @Test
    @DisplayName("secret-keyed properties return the redaction marker")
    void secretKeyedPropertiesReturnRedactionMarker() {
        SecretRedactingPropertySource source =
                new SecretRedactingPropertySource(sampleSource());

        assertThat(source.getProperty("spring.datasource.password"))
                .isEqualTo(SecretKeyMatcher.REDACTION);
        assertThat(source.getProperty("providers.json.api-key"))
                .isEqualTo(SecretKeyMatcher.REDACTION);
    }

    @Test
    @DisplayName("non-secret-keyed properties pass through to the delegate")
    void nonSecretKeyedPropertiesPassThrough() {
        SecretRedactingPropertySource source =
                new SecretRedactingPropertySource(sampleSource());

        assertThat(source.getProperty("spring.datasource.url"))
                .isEqualTo("jdbc:postgresql://db:5432/searchengine");
        assertThat(source.getProperty("spring.datasource.username"))
                .isEqualTo("appuser");
        assertThat(source.getProperty("providers.json.base-url"))
                .isEqualTo("https://json.example.com");
    }

    @Test
    @DisplayName("absent keys return null even if the key name looks secret")
    void absentKeyReturnsNullEvenWhenItLooksSecret() {
        SecretRedactingPropertySource source =
                new SecretRedactingPropertySource(sampleSource());

        assertThat(source.getProperty("missing.password")).isNull();
        assertThat(source.getProperty("missing.url")).isNull();
    }

    @Test
    @DisplayName("getPropertyNames forwards the underlying enumerable keys unchanged")
    void getPropertyNamesForwardsUnderlyingKeys() {
        MapPropertySource delegate = sampleSource();
        SecretRedactingPropertySource source =
                new SecretRedactingPropertySource(delegate);

        assertThat(source.getPropertyNames())
                .containsExactlyInAnyOrder(delegate.getPropertyNames());
    }

    @Test
    @DisplayName("non-enumerable delegates yield empty property names")
    void nonEnumerableDelegateYieldsEmptyPropertyNames() {
        PropertySource<Object> opaque = new PropertySource<>("opaque", new Object()) {
            @Override
            public Object getProperty(String name) {
                return name.equals("spring.datasource.password") ? "swordfish" : null;
            }
        };

        SecretRedactingPropertySource source = new SecretRedactingPropertySource(opaque);

        assertThat(source.getPropertyNames()).isEmpty();
        // Redaction still works for opaque sources.
        assertThat(source.getProperty("spring.datasource.password"))
                .isEqualTo(SecretKeyMatcher.REDACTION);
    }

    @Test
    @DisplayName("custom matcher overrides the default secret patterns")
    void customMatcherOverridesDefaults() {
        SecretKeyMatcher matcher = new SecretKeyMatcher(java.util.List.of("custom"));
        SecretRedactingPropertySource source =
                new SecretRedactingPropertySource("test", sampleSource(), matcher);

        // 'password' is no longer matched by the custom patterns.
        assertThat(source.getProperty("spring.datasource.password"))
                .isEqualTo("swordfish");
    }

    @Test
    @DisplayName("default constructor uses default name and default matcher")
    void defaultConstructorUsesDefaults() {
        MapPropertySource delegate = sampleSource();
        SecretRedactingPropertySource source = new SecretRedactingPropertySource(delegate);

        assertThat(source.getName()).isEqualTo(SecretRedactingPropertySource.DEFAULT_NAME);
        assertThat(source.matcher().patterns())
                .containsExactlyElementsOf(SecretKeyMatcher.DEFAULT_PATTERNS);
        assertThat(source.delegate()).isSameAs(delegate);
    }
}
