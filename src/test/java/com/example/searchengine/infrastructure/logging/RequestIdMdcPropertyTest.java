package com.example.searchengine.infrastructure.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import net.jqwik.api.ForAll;
import net.jqwik.api.GenerationMode;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for request-id MDC propagation through {@link RequestIdFilter}.
 *
 * <p><b>Validates: Requirements 16.1, 16.2</b></p>
 *
 * <p>For any sequence of {@code N} HTTP requests handled by {@code Search_API},
 * every JSON log entry emitted within a request's processing window carries a
 * non-null, non-empty {@code requestId} in its MDC field, and {@code requestId}
 * values across distinct requests are pairwise unique.</p>
 *
 * <p>The test attaches a Logback {@link ListAppender} to a dedicated logger so
 * that every event's MDC snapshot can be inspected. To exercise the
 * "no incoming header → freshly generated UUID" branch (the case where
 * uniqueness is the filter's responsibility, per the task description), every
 * generated request omits the {@code X-Request-Id} header so that
 * {@link RequestIdFilter} must mint a new id for each request.</p>
 */
class RequestIdMdcPropertyTest {

    private static final org.slf4j.Logger TEST_LOGGER =
            LoggerFactory.getLogger(RequestIdMdcPropertyTest.class);

    @Property(tries = 100, generation = GenerationMode.RANDOMIZED)
    @Label("Feature: search-engine-service, Property 12: Request-id MDC propagation")
    void requestIdIsPropagatedAndUniquePerRequest(
            @ForAll @IntRange(min = 1, max = 50) int requestCount)
            throws ServletException, IOException {

        Logger logbackLogger = (Logger) TEST_LOGGER;
        Level previousLevel = logbackLogger.getLevel();

        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
        appender.start();
        logbackLogger.addAppender(appender);
        logbackLogger.setLevel(Level.INFO);

        // Pre-condition: MDC must be clean before the run starts.
        MDC.clear();

        try {
            RequestIdFilter filter = new RequestIdFilter();
            Set<String> seenIds = new HashSet<>();

            for (int i = 0; i < requestCount; i++) {
                MockHttpServletRequest request = new MockHttpServletRequest();
                MockHttpServletResponse response = new MockHttpServletResponse();
                int eventsBefore = appender.list.size();

                FilterChain chain = (req, res) -> {
                    // Emit two log events from inside the filter chain so the
                    // MDC propagation property is exercised against multiple
                    // events per request, not just one.
                    TEST_LOGGER.info("inside-filter-chain-event-1");
                    TEST_LOGGER.info("inside-filter-chain-event-2");
                };

                filter.doFilter(request, response, chain);

                // (3, 5) MDC must be cleared after the filter chain completes
                // so that the request id cannot leak across pooled threads.
                assertThat(MDC.get(RequestIdFilter.MDC_KEY))
                        .as("MDC requestId must be cleared after filter chain completes")
                        .isNull();

                // Snapshot the log events emitted inside this request's window.
                List<ILoggingEvent> emittedThisRequest =
                        List.copyOf(appender.list.subList(eventsBefore, appender.list.size()));
                assertThat(emittedThisRequest)
                        .as("expected log events emitted within the filter chain for request #%d", i)
                        .isNotEmpty();

                String idForThisRequest = null;
                for (ILoggingEvent event : emittedThisRequest) {
                    String mdcId = event.getMDCPropertyMap().get(RequestIdFilter.MDC_KEY);

                    // (3) Every event from inside the filter chain carries a
                    //     non-null, non-empty requestId in its MDC.
                    assertThat(mdcId)
                            .as("log event emitted within filter chain must carry a non-null requestId in MDC")
                            .isNotNull();
                    assertThat(mdcId)
                            .as("log event emitted within filter chain must carry a non-empty requestId in MDC")
                            .isNotEmpty();

                    // All events in a single request's window share the same id.
                    if (idForThisRequest == null) {
                        idForThisRequest = mdcId;
                    } else {
                        assertThat(mdcId)
                                .as("all log events within a single request window share the same requestId")
                                .isEqualTo(idForThisRequest);
                    }
                }

                // Response must mirror the same id back to the caller.
                String responseHeader = response.getHeader(RequestIdFilter.HEADER_NAME);
                assertThat(responseHeader)
                        .as("response X-Request-Id header must mirror the MDC requestId")
                        .isEqualTo(idForThisRequest);

                seenIds.add(idForThisRequest);
            }

            // (4) Pairwise uniqueness across distinct requests.
            //     No incoming header was supplied, so every id is a freshly
            //     generated UUID and the set size must equal the number of
            //     requests in the run.
            assertThat(seenIds)
                    .as("requestId values across distinct requests must be pairwise unique")
                    .hasSize(requestCount);
        } finally {
            logbackLogger.detachAppender(appender);
            appender.stop();
            logbackLogger.setLevel(previousLevel);
            MDC.clear();
        }
    }
}
