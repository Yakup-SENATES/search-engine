package com.example.searchengine.infrastructure.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.DefaultBootstrapContext;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigValidatorTest {

    private ListAppender<ILoggingEvent> appender;
    private Logger validatorLogger;

    @BeforeEach
    void setUpLogCapture() {
        validatorLogger = (Logger) LoggerFactory.getLogger(ConfigValidator.class);
        appender = new ListAppender<>();
        appender.start();
        validatorLogger.addAppender(appender);
        validatorLogger.setLevel(Level.ERROR);
    }

    @AfterEach
    void tearDownLogCapture() {
        validatorLogger.detachAppender(appender);
        appender.stop();
    }

    @Test
    @DisplayName("does not exit when every required key is present and non-blank")
    void allKeysPresentDoesNotExit() {
        Map<String, Object> props = new HashMap<>();
        props.put("spring.datasource.url", "jdbc:postgresql://localhost:5432/test");
        props.put("spring.datasource.username", "user");
        props.put("spring.datasource.password", "secret");
        props.put("providers.json.base-url", "http://json.example.com");
        props.put("providers.xml.base-url", "http://xml.example.com");

        AtomicInteger exitCalls = new AtomicInteger();
        ConfigValidator validator = new ConfigValidator(code -> exitCalls.incrementAndGet());

        validator.onApplicationEvent(eventFor(props));

        assertThat(exitCalls).hasValue(0);
        assertThat(errorMessages()).isEmpty();
    }

    @Test
    @DisplayName("exits with code 1 and logs every missing key when all required keys are absent")
    void allKeysMissingExitsAndLogsEach() {
        AtomicInteger exitCode = new AtomicInteger(-1);
        ConfigValidator validator = new ConfigValidator(exitCode::set);

        validator.onApplicationEvent(eventFor(Map.of()));

        assertThat(exitCode).hasValue(1);
        List<String> messages = errorMessages();
        // One ERROR per missing key + one summary line.
        for (String key : ConfigValidator.REQUIRED_KEYS) {
            assertThat(messages)
                    .as("expected ERROR log naming missing key %s", key)
                    .anyMatch(m -> m.contains(key));
        }
        assertThat(messages).anyMatch(m -> m.contains("Aborting startup"));
    }

    @Test
    @DisplayName("treats blank values the same as missing values")
    void blankValueIsTreatedAsMissing() {
        Map<String, Object> props = new HashMap<>();
        props.put("spring.datasource.url", "jdbc:postgresql://localhost:5432/test");
        props.put("spring.datasource.username", "   "); // blank
        props.put("spring.datasource.password", "secret");
        props.put("providers.json.base-url", "");        // empty
        props.put("providers.xml.base-url", "http://xml.example.com");

        AtomicInteger exitCode = new AtomicInteger(-1);
        ConfigValidator validator = new ConfigValidator(exitCode::set);

        validator.onApplicationEvent(eventFor(props));

        assertThat(exitCode).hasValue(1);
        List<String> messages = errorMessages();
        assertThat(messages).anyMatch(m -> m.contains("spring.datasource.username"));
        assertThat(messages).anyMatch(m -> m.contains("providers.json.base-url"));
        assertThat(messages).noneMatch(m -> m.contains("spring.datasource.url:"));
        assertThat(messages).noneMatch(m -> m.contains("providers.xml.base-url:"));
    }

    @Test
    @DisplayName("validation completes well within the 10-second budget mandated by REQ 18.4")
    void completesWithinTenSeconds() {
        AtomicInteger exitCalls = new AtomicInteger();
        ConfigValidator validator = new ConfigValidator(code -> exitCalls.incrementAndGet());

        long start = System.nanoTime();
        validator.onApplicationEvent(eventFor(Map.of()));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertThat(elapsedMs).isLessThan(10_000L);
        assertThat(exitCalls).hasValue(1);
    }

    private List<String> errorMessages() {
        return appender.list.stream()
                .filter(e -> e.getLevel() == Level.ERROR)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
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
