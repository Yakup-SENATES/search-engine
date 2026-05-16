package com.example.searchengine.web.error;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.stereotype.Component;

/**
 * Replaces configured secret values with a fixed redaction marker before an error
 * message is emitted to the response body or the log (REQ 18.5).
 *
 * <p>Two complementary sources of secret values are supported:
 * <ol>
 *   <li><b>Explicit list</b> — the {@code secret.values} configuration property
 *       (comma-separated literal strings). Useful for tests and for any value
 *       that is not addressable through a property key (third-party tokens
 *       baked into log output, etc.).</li>
 *   <li><b>Environment auto-discovery</b> — when an {@link Environment} is
 *       supplied, every property whose key (case-insensitively) contains one
 *       of the secret patterns ({@code password}, {@code secret}, {@code token},
 *       {@code api-key}, {@code api_key}, {@code apikey}, {@code credential})
 *       contributes its value to the redaction set. This mirrors the
 *       {@code SecretRedactingPropertySource} behaviour so user-facing error
 *       bodies and log output share a single secret-key list.</li>
 * </ol>
 *
 * <p>Any value present in the combined set is replaced with
 * {@value #REDACTION} wherever it appears in an outgoing message. The default
 * (no explicit list, no environment) is a no-op.
 */
@Component
public class ErrorMessageSanitizer {

    /**
     * Marker substituted in place of any matched secret value. Mirrors the
     * literal documented in {@code design.md} § Secret redaction (REQ 18.5).
     */
    public static final String REDACTION = "***REDACTED***";

    /**
     * Default substrings that mark a property key as holding a secret value.
     * Kept in sync with {@code SecretKeyMatcher.DEFAULT_PATTERNS} in the
     * infrastructure layer; duplicated here so the web layer does not need
     * to import the infrastructure layer (an ArchUnit-enforced rule).
     */
    public static final List<String> SECRET_KEY_PATTERNS = List.of(
            "password",
            "secret",
            "token",
            "api-key",
            "api_key",
            "apikey",
            "credential"
    );

    private final List<String> secrets;

    /**
     * Spring-friendly constructor. Reads the comma-separated {@code secret.values}
     * property and, when present, scans the {@link Environment} for every
     * property key matching {@link #SECRET_KEY_PATTERNS} so that those values
     * are also redacted.
     */
    @Autowired
    public ErrorMessageSanitizer(
            @Value("${secret.values:}") String[] secretValues,
            Environment environment) {
        this(combine(toList(secretValues), discoverSecretsFromEnvironment(environment)));
    }

    /**
     * Test-friendly constructor that accepts an explicit list of secret values.
     * A {@code null} list is treated as empty.
     */
    public ErrorMessageSanitizer(List<String> secrets) {
        this.secrets = secrets == null ? Collections.emptyList() : List.copyOf(deduplicate(secrets));
    }

    /**
     * Returns {@code message} with every configured secret value replaced by
     * {@value #REDACTION}. {@code null} input returns {@code null} so callers can
     * pipe handler messages straight through.
     */
    public String sanitize(String message) {
        if (message == null || message.isEmpty() || secrets.isEmpty()) {
            return message;
        }
        String result = message;
        for (String secret : secrets) {
            result = result.replace(secret, REDACTION);
        }
        return result;
    }

    /** Visible for tests; returns the resolved secret list. */
    List<String> secrets() {
        return secrets;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static List<String> toList(String[] values) {
        return Arrays.stream(values == null ? new String[0] : values)
                .filter(s -> s != null && !s.isBlank())
                .toList();
    }

    private static List<String> combine(List<String> a, List<String> b) {
        List<String> combined = new ArrayList<>(a.size() + b.size());
        combined.addAll(a);
        combined.addAll(b);
        return combined;
    }

    private static List<String> deduplicate(List<String> values) {
        Set<String> seen = new LinkedHashSet<>();
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                seen.add(v);
            }
        }
        return new ArrayList<>(seen);
    }

    /**
     * Walks the resolved {@link Environment} looking for property keys whose
     * (lower-cased) name contains one of the {@link #SECRET_KEY_PATTERNS}, and
     * collects the resolved values. Only {@link EnumerablePropertySource}s are
     * iterated, so non-enumerable sources (e.g. system properties accessed via
     * indirection) contribute nothing extra — but the value still gets redacted
     * if the property is also present in an enumerable source like the YAML or
     * the OS environment.
     */
    static List<String> discoverSecretsFromEnvironment(Environment environment) {
        if (!(environment instanceof ConfigurableEnvironment configurable)) {
            return List.of();
        }
        List<String> discovered = new ArrayList<>();
        for (PropertySource<?> source : configurable.getPropertySources()) {
            if (!(source instanceof EnumerablePropertySource<?> enumerable)) {
                continue;
            }
            for (String key : enumerable.getPropertyNames()) {
                if (!isSecretKey(key)) {
                    continue;
                }
                // Use the environment lookup so placeholders like ${DB_PASSWORD}
                // are resolved to the actual secret string before redaction.
                String value = environment.getProperty(key);
                if (value != null && !value.isEmpty() && !REDACTION.equals(value)) {
                    discovered.add(value);
                }
            }
        }
        return discovered;
    }

    private static boolean isSecretKey(String key) {
        if (key == null || key.isEmpty()) {
            return false;
        }
        String lower = key.toLowerCase(Locale.ROOT);
        for (String pattern : SECRET_KEY_PATTERNS) {
            if (lower.contains(pattern)) {
                return true;
            }
        }
        return false;
    }
}
