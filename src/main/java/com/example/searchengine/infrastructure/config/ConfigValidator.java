package com.example.searchengine.infrastructure.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.ConfigurableEnvironment;

import java.util.ArrayList;
import java.util.List;

/**
 * Validates that every property declared as required is present and non-blank
 * in the resolved environment, before any HTTP server, database connection,
 * provider client, or scheduled job is initialized (REQ 18.3, 18.4).
 *
 * <p>This listener fires on {@link ApplicationEnvironmentPreparedEvent}, which is
 * dispatched after the {@code Environment} has been populated from
 * {@code application.yaml} and environment variables but <em>before</em> the
 * Spring application context (and therefore any beans) is created. This is
 * intentionally earlier than {@code ApplicationStartingEvent}, where the
 * environment is not yet available, but still early enough to prevent any
 * infrastructure component from being initialized when configuration is invalid.</p>
 *
 * <p>If one or more required properties are missing or blank, the listener:
 * <ol>
 *   <li>Logs an {@code ERROR} entry per missing key, naming the Spring property
 *       key (REQ 18.4).</li>
 *   <li>Terminates the JVM with a non-zero exit code via {@link System#exit(int)},
 *       within a single synchronous loop that completes in well under 10 seconds
 *       (REQ 18.4).</li>
 * </ol>
 *
 * <p>Because this runs before bean instantiation, no HTTP listener is bound,
 * no JDBC pool is created, no Redis connection is opened, and the scheduler
 * never starts.</p>
 *
 * <p>The listener is registered explicitly via
 * {@code SpringApplication.addListeners(...)} from the main application class,
 * not through component scanning, so it activates regardless of whether the
 * application context is ever created.</p>
 */
public class ConfigValidator implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

    private static final Logger log = LoggerFactory.getLogger(ConfigValidator.class);

    /**
     * Spring property keys that MUST be present and non-blank for the service to start.
     * These map to the environment variables documented in the README:
     * {@code DB_URL}, {@code DB_USERNAME}, {@code DB_PASSWORD},
     * {@code PROVIDER_JSON_URL}, {@code PROVIDER_XML_URL}.
     */
    static final List<String> REQUIRED_KEYS = List.of(
            "spring.datasource.url",
            "spring.datasource.username",
            "spring.datasource.password",
            "providers.json.base-url",
            "providers.xml.base-url"
    );

    /** Indirection point so unit tests can substitute a non-terminating exit handler. */
    private final ExitHandler exitHandler;

    public ConfigValidator() {
        this(code -> System.exit(code));
    }

    ConfigValidator(ExitHandler exitHandler) {
        this.exitHandler = exitHandler;
    }

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        ConfigurableEnvironment environment = event.getEnvironment();
        List<String> missing = findMissingKeys(environment);

        if (missing.isEmpty()) {
            return;
        }

        for (String key : missing) {
            log.error("Required configuration property is missing or blank: {}", key);
        }
        log.error(
                "Aborting startup: {} required configuration propert{} missing. "
                        + "No HTTP server, database pool, provider client, or scheduler will be initialized.",
                missing.size(),
                missing.size() == 1 ? "y is" : "ies are"
        );

        exitHandler.exit(1);
    }

    /**
     * Visible for testing. Returns the subset of {@link #REQUIRED_KEYS} that is
     * absent or resolves to a blank value in the supplied environment.
     */
    static List<String> findMissingKeys(ConfigurableEnvironment environment) {
        List<String> missing = new ArrayList<>();
        for (String key : REQUIRED_KEYS) {
            String value = environment.getProperty(key);
            if (value == null || value.isBlank()) {
                missing.add(key);
            }
        }
        return missing;
    }

    /** Strategy for terminating the JVM; allows substitution in tests. */
    @FunctionalInterface
    interface ExitHandler {
        void exit(int code);
    }
}
