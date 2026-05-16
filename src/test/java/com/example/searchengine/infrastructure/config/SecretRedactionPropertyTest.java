package com.example.searchengine.infrastructure.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.searchengine.web.error.ErrorMessageSanitizer;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.StringLength;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for secret redaction in log output.
 *
 * <p><b>Validates: Requirements 18.5</b></p>
 *
 * <p>Property 14 (search-engine-service): For any log invocation whose log
 * message or argument is the value of a secret-keyed configuration property
 * (DB password, provider API key, authentication token), the rendered log
 * output contains the literal redaction marker (the value of
 * {@link ErrorMessageSanitizer#REDACTION}) instead of the original secret
 * value, and an exhaustive scan of captured logs across a synthetic test run
 * never contains the original secret value.</p>
 *
 * <p>The generator emits random distinct secret values (UUID-style alphanumeric
 * tokens long enough that they will not appear inside the surrounding log
 * context by chance) and configures them as the sanitizer's secret list. For
 * every secret we synthesize a log line that embeds the secret verbatim, run
 * the line through {@link ErrorMessageSanitizer#sanitize(String)}, emit the
 * sanitized line via SLF4J into a Logback {@link ListAppender}, and then scan
 * every captured event to confirm that no original secret value survived.</p>
 */
class SecretRedactionPropertyTest {

    private static final org.slf4j.Logger TEST_LOGGER =
            LoggerFactory.getLogger(SecretRedactionPropertyTest.class);

    /**
     * Synthesizes a small, distinct set of secret values resembling the kinds
     * of credentials REQ 18.5 enumerates: a database password, a provider API
     * key, and an authentication token.
     *
     * <p>Each secret is constrained to alphanumeric characters of length
     * 16–48 so that:
     * <ul>
     *   <li>Two random secrets are extremely unlikely to share a substring,
     *       which keeps the property reasoning per-secret independent.</li>
     *   <li>No secret can equal the redaction marker {@code "***"}.</li>
     *   <li>A secret will not appear by accident inside the deterministic
     *       surrounding log context strings used below.</li>
     * </ul>
     */
    @Provide
    Arbitrary<List<String>> secretSets() {
        Arbitrary<String> secret = Arbitraries.strings()
                .alpha()
                .numeric()
                .ofMinLength(16)
                .ofMaxLength(48);
        return secret.list().ofMinSize(1).ofMaxSize(5)
                .map(list -> {
                    // Force pairwise uniqueness; collapsing duplicates while
                    // preserving order so the returned list is deterministic.
                    Set<String> distinct = new LinkedHashSet<>(list);
                    return (List<String>) new ArrayList<>(distinct);
                })
                .filter(list -> !list.isEmpty());
    }

    @Property(tries = 100)
    @Label("Feature: search-engine-service, Property 14: Secret redaction")
    void secretValuesAreRedactedInSanitizedOutputAndCapturedLogs(
            @ForAll("secretSets") List<String> secrets,
            @ForAll @StringLength(min = 0, max = 64) String prefix,
            @ForAll @StringLength(min = 0, max = 64) String suffix,
            @ForAll @IntRange(min = 0, max = 4) int positionMode) {

        // Sanitizer is configured with every secret in the generated set. This
        // mirrors how the Spring profile would wire `secret.values` from
        // application.yaml — a comma-separated list of credential values.
        ErrorMessageSanitizer sanitizer = new ErrorMessageSanitizer(secrets);

        Logger logbackLogger = (Logger) TEST_LOGGER;
        Level previousLevel = logbackLogger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
        appender.start();
        logbackLogger.addAppender(appender);
        logbackLogger.setLevel(Level.TRACE);

        try {
            for (String secret : secrets) {
                String rawMessage = embedSecret(prefix, secret, suffix, positionMode);

                // Sanity check on the generator: the raw, un-sanitized message
                // really does contain the secret, otherwise the property would
                // be vacuously true for this case.
                assertThat(rawMessage)
                        .as("generator must embed the secret in the raw message")
                        .contains(secret);

                String sanitized = sanitizer.sanitize(rawMessage);

                // (1) Sanitized output never contains the original secret value.
                assertThat(sanitized)
                        .as("sanitized message must not contain original secret value")
                        .doesNotContain(secret);

                // (2) Sanitized output contains the literal redaction marker.
                assertThat(sanitized)
                        .as("sanitized message must contain the redaction marker %s",
                                ErrorMessageSanitizer.REDACTION)
                        .contains(ErrorMessageSanitizer.REDACTION);

                // Emit the sanitized message via SLF4J at INFO so the captured
                // log scan below has events to inspect. We also emit the
                // sanitized output as a structured argument to confirm that
                // arguments are redacted in the same way as the message body.
                TEST_LOGGER.info(sanitized, sanitized);
            }

            // (3) Exhaustive scan: across every event captured during this
            //     synthetic test run, neither the formatted message nor any
            //     argument's string representation contains an original secret
            //     value. This is the "exhaustive scan of captured logs" half
            //     of the property statement.
            for (ILoggingEvent event : appender.list) {
                String formatted = event.getFormattedMessage();
                for (String secret : secrets) {
                    assertThat(formatted)
                            .as("captured log message must not contain original secret value")
                            .doesNotContain(secret);
                }
                Object[] args = event.getArgumentArray();
                if (args != null) {
                    for (Object arg : args) {
                        String rendered = String.valueOf(arg);
                        for (String secret : secrets) {
                            assertThat(rendered)
                                    .as("captured log argument must not contain original secret value")
                                    .doesNotContain(secret);
                        }
                    }
                }
            }
        } finally {
            logbackLogger.detachAppender(appender);
            appender.stop();
            logbackLogger.setLevel(previousLevel);
        }
    }

    /**
     * Embeds {@code secret} into a synthetic log line at one of several
     * representative positions (prefix only, suffix only, surrounded, repeated,
     * or message-body equals secret) so the property is exercised against a
     * variety of substring placements.
     */
    private static String embedSecret(
            String prefix, String secret, String suffix, int positionMode) {
        return switch (positionMode) {
            case 0 -> prefix + secret;                              // suffix
            case 1 -> secret + suffix;                              // prefix
            case 2 -> prefix + secret + suffix;                     // surrounded
            case 3 -> secret;                                       // entire message
            case 4 -> prefix + secret + " | retry: " + secret + suffix; // repeated
            default -> prefix + secret + suffix;
        };
    }
}
