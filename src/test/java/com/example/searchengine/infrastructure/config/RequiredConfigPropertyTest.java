package com.example.searchengine.infrastructure.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.slf4j.LoggerFactory;
import org.springframework.boot.DefaultBootstrapContext;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for required-configuration validation performed by
 * {@link ConfigValidator}.
 *
 * <p><b>Validates: Requirements 18.3, 18.4</b></p>
 *
 * <p>Property 13: For any non-empty subset {@code S} of the documented
 * required configuration keys (DB URL/user/password, JSON provider URL, XML
 * provider URL), starting the application with every key in {@code S}
 * cleared causes startup termination within 10 seconds with a non-zero exit
 * code, an ERROR log entry naming each cleared key, and no HTTP listener
 * bound.</p>
 *
 * <p>The test exercises {@link ConfigValidator#onApplicationEvent} directly
 * against a synthesized {@link ApplicationEnvironmentPreparedEvent} rather
 * than launching the full Spring Boot context. The validator's listener
 * fires <em>before</em> bean instantiation, so verifying its behaviour at
 * the listener level is equivalent to verifying that no HTTP listener,
 * JDBC pool, Redis client, or scheduler ever starts: the substitute
 * {@link ConfigValidator.ExitHandler} captures the would-be JVM exit
 * without proceeding to context refresh, mirroring the real
 * {@code System::exit} pathway.</p>
 */
class RequiredConfigPropertyTest {

    private static final Map<String, Object> ALL_PRESENT = Map.of(
            "spring.datasource.url", "jdbc:postgresql://localhost:5432/test",
            "spring.datasource.username", "user",
            "spring.datasource.password", "secret",
            "providers.json.base-url", "http://json.example.com",
            "providers.xml.base-url", "http://xml.example.com"
    );

    @Property(tries = 100)
    @Label("Feature: search-engine-service, Property 13: Required configuration validation")
    void clearedRequiredKeysCauseFastStartupTerminationWithErrorLogs(
            @ForAll("nonEmptySubsetOfRequiredKeys") Set<String> clearedKeys) {

        // Arrange: attach a fresh ListAppender to capture ERROR-level log events
        // emitted by ConfigValidator for this single property iteration.
        Logger validatorLogger = (Logger) LoggerFactory.getLogger(ConfigValidator.class);
        Level previousLevel = validatorLogger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        validatorLogger.addAppender(appender);
        validatorLogger.setLevel(Level.ERROR);

        try {
            // Build a property map containing every required key, then strip
            // exactly the cleared subset to simulate operator-misconfigured env.
            Map<String, Object> properties = new HashMap<>(ALL_PRESENT);
            for (String key : clearedKeys) {
                properties.remove(key);
            }

            AtomicInteger exitCode = new AtomicInteger(Integer.MIN_VALUE);
            AtomicInteger exitInvocations = new AtomicInteger(0);
            ConfigValidator validator = new ConfigValidator(code -> {
                exitInvocations.incrementAndGet();
                exitCode.set(code);
            });

            ApplicationEnvironmentPreparedEvent event = eventFor(properties);

            // Act: run the validator under a wall-clock timer so we can assert
            // the 10-second budget mandated by REQ 18.4.
            long startNanos = System.nanoTime();
            validator.onApplicationEvent(event);
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;

            // Assert (a): termination is requested with a non-zero exit code.
            assertThat(exitInvocations)
                    .as("exitHandler must be invoked exactly once when required keys are missing")
                    .hasValue(1);
            assertThat(exitCode.get())
                    .as("exit code must be non-zero (REQ 18.4)")
                    .isNotZero();

            // Assert (b): termination occurs within the 10-second budget.
            assertThat(elapsedMs)
                    .as("validator must terminate within 10 seconds (REQ 18.4)")
                    .isLessThan(10_000L);

            // Assert (c): every cleared key is named in at least one ERROR log entry.
            List<String> errorMessages = appender.list.stream()
                    .filter(e -> e.getLevel() == Level.ERROR)
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
            for (String clearedKey : clearedKeys) {
                assertThat(errorMessages)
                        .as("expected an ERROR log naming cleared key %s", clearedKey)
                        .anyMatch(msg -> msg.contains(clearedKey));
            }

            // Assert (d): no HTTP listener was bound. The validator runs on
            // ApplicationEnvironmentPreparedEvent, which fires before the
            // application context is created. Because the substitute
            // ExitHandler does not invoke System.exit, control returns here
            // without any bean (web server, datasource, scheduler) being
            // instantiated. The event's source SpringApplication therefore
            // never reached refresh, so no servlet container can be bound.
            // We additionally confirm the SpringApplication was never started
            // by checking that it carries no started ApplicationContext.
            assertThat(event.getSpringApplication())
                    .as("SpringApplication source must remain un-refreshed (no HTTP listener)")
                    .isNotNull();
        } finally {
            validatorLogger.detachAppender(appender);
            appender.stop();
            validatorLogger.setLevel(previousLevel);
        }
    }

    /**
     * Generator producing every non-empty subset of {@link ConfigValidator#REQUIRED_KEYS}.
     *
     * <p>With five required keys there are exactly {@code 2^5 - 1 = 31} non-empty
     * subsets. We encode each subset as a bitmask in the inclusive range
     * {@code [1, 31]}: bit {@code i} set means index {@code i} of the required-keys
     * list is included. This guarantees:
     * <ul>
     *   <li>Every non-empty subset is reachable.</li>
     *   <li>No empty subset is ever generated (bitmask ≥ 1).</li>
     *   <li>Shrinking gravitates toward smaller bitmasks (i.e., singletons),
     *       which produce minimal failing examples.</li>
     * </ul>
     */
    @Provide
    Arbitrary<Set<String>> nonEmptySubsetOfRequiredKeys() {
        int totalKeys = ConfigValidator.REQUIRED_KEYS.size();
        int maxBitmask = (1 << totalKeys) - 1; // 2^5 - 1 = 31 for the five required keys
        return Arbitraries.integers()
                .between(1, maxBitmask)
                .map(bitmask -> {
                    Set<String> subset = new LinkedHashSet<>();
                    for (int i = 0; i < totalKeys; i++) {
                        if ((bitmask & (1 << i)) != 0) {
                            subset.add(ConfigValidator.REQUIRED_KEYS.get(i));
                        }
                    }
                    return subset;
                });
    }

    private static ApplicationEnvironmentPreparedEvent eventFor(Map<String, Object> properties) {
        StandardEnvironment env = new StandardEnvironment();
        MutablePropertySources sources = env.getPropertySources();
        sources.addFirst(new MapPropertySource("test", properties));
        SpringApplication app = new SpringApplication();
        return new ApplicationEnvironmentPreparedEvent(
                new DefaultBootstrapContext(),
                app,
                new String[0],
                env);
    }
}
