package com.example.searchengine.infrastructure.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Unit tests for {@link LoggingOnlyRateLimitFilter}.
 *
 * <p>Validates Requirement 13.5: when {@code ratelimit.enabled=false} the filter must
 * forward every request without enforcement, but emit an INFO log entry naming the IP
 * for each request that <em>would</em> have been rejected.
 */
class LoggingOnlyRateLimitFilterTest {

    private static final String API_PATH = "/api/v1/search";

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(LoggingOnlyRateLimitFilter.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
    }

    private LoggingOnlyRateLimitFilter newFilter(int requestsPerWindow, long windowSeconds) {
        RateLimitProperties props = new RateLimitProperties();
        props.setEnabled(false);
        props.setRequestsPerWindow(requestsPerWindow);
        props.setWindowSeconds(windowSeconds);
        return new LoggingOnlyRateLimitFilter(props, new ClientIpResolver());
    }

    @Test
    void alwaysForwardsRequestsRegardlessOfBudget() throws ServletException, IOException {
        LoggingOnlyRateLimitFilter filter = newFilter(2, 60L);
        AtomicInteger downstream = new AtomicInteger();
        FilterChain chain = (req, res) -> downstream.incrementAndGet();

        for (int i = 0; i < 5; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", API_PATH);
            request.setRemoteAddr("198.51.100.50");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, chain);
            assertThat(response.getStatus()).isEqualTo(200);
        }

        assertThat(downstream.get()).isEqualTo(5);
    }

    @Test
    void emitsInfoLogForRequestsThatWouldHaveBeenRejected() throws ServletException, IOException {
        LoggingOnlyRateLimitFilter filter = newFilter(1, 60L);
        FilterChain chain = (req, res) -> {};

        for (int i = 0; i < 4; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", API_PATH);
            request.setRemoteAddr("198.51.100.60");
            filter.doFilter(request, new MockHttpServletResponse(), chain);
        }

        List<ILoggingEvent> infoEvents = appender.list.stream()
                .filter(e -> e.getLevel() == Level.INFO)
                .toList();

        // First request consumes the only token; the next 3 would have been rejected.
        assertThat(infoEvents).hasSize(3);
        assertThat(infoEvents).allSatisfy(event ->
                assertThat(event.getFormattedMessage())
                        .contains("198.51.100.60")
                        .contains("clientIp=")
        );
    }

    @Test
    void doesNotApplyToNonApiPaths() throws ServletException, IOException {
        LoggingOnlyRateLimitFilter filter = newFilter(1, 60L);
        AtomicInteger downstream = new AtomicInteger();
        FilterChain chain = (req, res) -> downstream.incrementAndGet();

        for (int i = 0; i < 3; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/dashboard");
            request.setRemoteAddr("198.51.100.70");
            filter.doFilter(request, new MockHttpServletResponse(), chain);
        }

        assertThat(downstream.get()).isEqualTo(3);
        assertThat(appender.list.stream().filter(e -> e.getLevel() == Level.INFO))
                .as("non-API paths must not produce 'would have rejected' logs")
                .isEmpty();
    }
}
