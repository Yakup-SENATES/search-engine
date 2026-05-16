package com.example.searchengine.infrastructure.config;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Matches Spring property keys that name a secret value (database password,
 * provider API key, authentication token, etc.) and produces a fixed redaction
 * marker for those values (REQ 18.5).
 *
 * <p>The matcher is a tiny pure-Java utility with no Spring dependencies. The
 * default pattern list — {@link #DEFAULT_PATTERNS} — covers the substrings the
 * Twelve-Factor configuration convention uses for credentials: {@code password},
 * {@code secret}, {@code token}, {@code api-key}, {@code api_key},
 * {@code apikey}, and {@code credential}. A property key is treated as secret
 * when, lower-cased, it contains any of these substrings.
 *
 * <p>The same patterns are used by {@link SecretRedactingPropertySource} so that
 * a {@code env.getProperty("spring.datasource.password")} lookup intended for
 * log output returns {@link #REDACTION} instead of the underlying value, and by
 * {@code ErrorMessageSanitizer} (in the web layer) so that user-facing error
 * messages never leak the same secret values.
 */
public final class SecretKeyMatcher {

    /** Fixed marker substituted for any secret value. Mirrors {@code design.md} § Secret redaction. */
    public static final String REDACTION = "***REDACTED***";

    /**
     * Default substrings that mark a property key as holding a secret. Keys are
     * compared case-insensitively, so e.g. {@code spring.datasource.password},
     * {@code PROVIDER_API_KEY}, and {@code auth.token} are all matched.
     */
    public static final List<String> DEFAULT_PATTERNS = List.of(
            "password",
            "secret",
            "token",
            "api-key",
            "api_key",
            "apikey",
            "credential"
    );

    private final List<String> patterns;

    /** Creates a matcher with the {@link #DEFAULT_PATTERNS}. */
    public SecretKeyMatcher() {
        this(DEFAULT_PATTERNS);
    }

    /**
     * Creates a matcher with a custom pattern list. Patterns are stored
     * lower-cased and compared as substrings.
     *
     * @param patterns non-null list of substrings to treat as secret indicators
     */
    public SecretKeyMatcher(List<String> patterns) {
        Objects.requireNonNull(patterns, "patterns");
        this.patterns = patterns.stream()
                .filter(Objects::nonNull)
                .filter(p -> !p.isBlank())
                .map(p -> p.toLowerCase(Locale.ROOT))
                .toList();
    }

    /**
     * Returns {@code true} if {@code key} (case-insensitively) contains any of
     * the configured patterns. {@code null} or empty keys are not secrets.
     */
    public boolean isSecret(String key) {
        if (key == null || key.isEmpty()) {
            return false;
        }
        String lower = key.toLowerCase(Locale.ROOT);
        for (String pattern : patterns) {
            if (lower.contains(pattern)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns {@link #REDACTION} if {@code key} is secret, otherwise the
     * supplied {@code value} unchanged. {@code null} value is returned as
     * {@code null} for non-secret keys so callers can distinguish "absent" from
     * "redacted".
     */
    public String redactValue(String key, String value) {
        return isSecret(key) ? REDACTION : value;
    }

    /** Returns the configured patterns (immutable). Useful for tests. */
    public List<String> patterns() {
        return patterns;
    }
}
