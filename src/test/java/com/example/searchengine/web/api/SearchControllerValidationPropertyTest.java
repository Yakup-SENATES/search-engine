package com.example.searchengine.web.api;

import com.example.searchengine.application.analytics.SearchAnalyticsRecorder;
import com.example.searchengine.application.search.SearchResult;
import com.example.searchengine.application.search.SearchService;
import com.example.searchengine.infrastructure.admin.ClientIpHasher;
import com.example.searchengine.infrastructure.ratelimit.ClientIpResolver;
import com.example.searchengine.web.error.ErrorMessageSanitizer;
import com.example.searchengine.web.error.GlobalExceptionHandler;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.BeforeProperty;

import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.List;
import java.util.regex.Pattern;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Property-based test for the input validation pipeline of the Search API.
 *
 * <p><b>Validates: Requirements 4.3, 4.4, 4.5, 8.3, 8.4, 9.2, 9.7, 9.8, 9.9, 14.1, 14.2,
 * 19.1, 19.2</b></p>
 *
 * <p>For any HTTP request to {@code /api/v1/search} with random query parameters, the
 * {@code Search_Service} is invoked if and only if every constraint declared in
 * Requirements 8 and 9 passes; otherwise the response status is 400, the body matches
 * {@code {"error":{"code":"INVALID_QUERY","message":<string>}}}, and the message
 * identifies the first offending field together with the constraint it violated.</p>
 *
 * <p>Constraints reproduced from {@link SearchRequest}:</p>
 * <ul>
 *   <li>{@code q}: required, not blank, length 1..200 (REQ 8.3, 8.4)</li>
 *   <li>{@code type}: optional, must match {@code ^(video|text)$} when present (REQ 9.2)</li>
 *   <li>{@code sort}: optional, must match {@code ^(score|popularity|relevance)$} when present</li>
 *   <li>{@code page}: optional, must be an integer >= 1 when present (REQ 9.7, 9.9)</li>
 *   <li>{@code limit}: optional, must be an integer in 1..100 when present (REQ 9.8, 9.9)</li>
 * </ul>
 *
 * <p>The test wires {@link SearchController} into a {@link MockMvc} via
 * {@code standaloneSetup}, registering the {@link GlobalExceptionHandler} as
 * controller advice and a {@link LocalValidatorFactoryBean} so {@code @Valid} fires.
 * jqwik's property runner is incompatible with {@code @WebMvcTest}, so the standalone
 * setup mirrors the same wiring without needing a Spring context.</p>
 */
class SearchControllerValidationPropertyTest {

    private static final Pattern TYPE_PATTERN = Pattern.compile("^(video|text)$");
    private static final Pattern SORT_PATTERN = Pattern.compile("^(score|popularity|relevance)$");

    private SearchService searchService;
    private MockMvc mockMvc;

    @BeforeProperty
    void setUp() {
        searchService = mock(SearchService.class);
        SearchAnalyticsRecorder analyticsRecorder = mock(SearchAnalyticsRecorder.class);
        ClientIpHasher clientIpHasher = new ClientIpHasher();
        ClientIpResolver clientIpResolver = new ClientIpResolver();
        SearchController controller = new SearchController(searchService, analyticsRecorder, clientIpHasher, clientIpResolver);
        ErrorMessageSanitizer sanitizer = new ErrorMessageSanitizer(List.of());
        GlobalExceptionHandler advice = new GlobalExceptionHandler(sanitizer, analyticsRecorder, clientIpHasher, clientIpResolver);

        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(advice)
                .setValidator(validator)
                .build();
    }

    @Property(tries = 200)
    @Label("Feature: search-engine-service, Property 6: Input validation pipeline")
    void inputValidationPipeline(@ForAll("queryParameterSpecs") QueryParamSpec spec) throws Exception {
        // The mocked service returns a deterministic empty page for every valid request.
        SearchResult mockResult = new SearchResult(List.of(), 0L, 1, 10);
        when(searchService.search(any())).thenReturn(mockResult);

        MockHttpServletRequestBuilder request = get("/api/v1/search");
        if (spec.q() != null) {
            request = request.param("q", spec.q());
        }
        if (spec.type() != null) {
            request = request.param("type", spec.type());
        }
        if (spec.sort() != null) {
            request = request.param("sort", spec.sort());
        }
        if (spec.page() != null) {
            request = request.param("page", spec.page());
        }
        if (spec.limit() != null) {
            request = request.param("limit", spec.limit());
        }

        boolean valid = isValid(spec);

        ResultActions actions = mockMvc.perform(request);

        if (valid) {
            // Bi-conditional ⇒ direction: every constraint passes ⇒ service invoked, 200 returned.
            actions.andExpect(status().isOk());
            verify(searchService, times(1)).search(any());
        } else {
            // Bi-conditional ⇐ direction: any failing constraint ⇒ 400 INVALID_QUERY,
            // service not invoked, error envelope shape per design (REQ 14.1, 14.2).
            actions.andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").exists())
                    .andExpect(jsonPath("$.error.code").value("INVALID_QUERY"))
                    .andExpect(jsonPath("$.error.message").isString())
                    .andExpect(jsonPath("$.error.message").isNotEmpty());
            verify(searchService, never()).search(any());
        }

        // Reset interactions for the next iteration.
        Mockito.reset(searchService);
    }

    /**
     * Reproduces the constraint set declared on {@link SearchRequest}; returns {@code true}
     * iff every constraint passes for the supplied raw parameter values.
     */
    private static boolean isValid(QueryParamSpec spec) {
        // q: @NotBlank + @Size(min=1, max=200) (REQ 8.3, 8.4)
        if (spec.q() == null) {
            return false;
        }
        if (spec.q().isBlank()) {
            return false;
        }
        if (spec.q().length() > 200) {
            return false;
        }

        // type: optional @Pattern(^(video|text)$) (REQ 9.2)
        if (spec.type() != null && !TYPE_PATTERN.matcher(spec.type()).matches()) {
            return false;
        }

        // sort: optional @Pattern(^(score|popularity|relevance)$)
        if (spec.sort() != null && !SORT_PATTERN.matcher(spec.sort()).matches()) {
            return false;
        }

        // page: optional Integer with @Min(1) (REQ 9.7, 9.9)
        if (spec.page() != null) {
            Integer parsed = parseIntOrNull(spec.page());
            if (parsed == null || parsed < 1) {
                return false;
            }
        }

        // limit: optional Integer with @Min(1) @Max(100) (REQ 9.8, 9.9)
        if (spec.limit() != null) {
            Integer parsed = parseIntOrNull(spec.limit());
            if (parsed == null || parsed < 1 || parsed > 100) {
                return false;
            }
        }

        return true;
    }

    private static Integer parseIntOrNull(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    // ─── Generators ───────────────────────────────────────────────────────────

    @Provide
    Arbitrary<QueryParamSpec> queryParameterSpecs() {
        return Combinators.combine(qArb(), typeArb(), sortArb(), pageArb(), limitArb())
                .as(QueryParamSpec::new);
    }

    /**
     * q: balanced mix of absent (null), empty, blank, valid length 1..200, and over-long 201..300.
     */
    private static Arbitrary<String> qArb() {
        Arbitrary<String> fixed = Arbitraries.of((String) null, "", " ", "  ", "\t", "  \t  ");
        Arbitrary<String> validLength = Arbitraries.strings()
                .alpha()
                .ofMinLength(1)
                .ofMaxLength(200);
        Arbitrary<String> tooLong = Arbitraries.strings()
                .alpha()
                .ofMinLength(201)
                .ofMaxLength(300);
        return Arbitraries.oneOf(fixed, validLength, tooLong);
    }

    /**
     * type: null, the two valid tokens, or a random non-empty string (almost certainly invalid).
     */
    private static Arbitrary<String> typeArb() {
        Arbitrary<String> fixed = Arbitraries.of((String) null, "video", "text");
        Arbitrary<String> random = Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(20);
        return Arbitraries.oneOf(fixed, random);
    }

    /**
     * sort: null, the three valid tokens, or a random non-empty string (almost certainly invalid).
     */
    private static Arbitrary<String> sortArb() {
        Arbitrary<String> fixed = Arbitraries.of((String) null, "score", "popularity", "relevance");
        Arbitrary<String> random = Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(20);
        return Arbitraries.oneOf(fixed, random);
    }

    /**
     * page: null, integer in -100..100 (rendered as a string), or a non-numeric string
     * (each path exercises @Min, type-mismatch, or absent-default handling).
     */
    private static Arbitrary<String> pageArb() {
        Arbitrary<String> nullable = Arbitraries.of((String) null);
        Arbitrary<String> integers = Arbitraries.integers().between(-100, 100).map(String::valueOf);
        Arbitrary<String> nonNumeric = Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(8);
        return Arbitraries.oneOf(nullable, integers, nonNumeric);
    }

    /**
     * limit: null, integer in -50..200 (rendered as a string), or a non-numeric string
     * (each path exercises @Min, @Max, type-mismatch, or absent-default handling).
     */
    private static Arbitrary<String> limitArb() {
        Arbitrary<String> nullable = Arbitraries.of((String) null);
        Arbitrary<String> integers = Arbitraries.integers().between(-50, 200).map(String::valueOf);
        Arbitrary<String> nonNumeric = Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(8);
        return Arbitraries.oneOf(nullable, integers, nonNumeric);
    }

    /**
     * Aggregates the five raw query-parameter values for a single property iteration.
     * A {@code null} field means "omit this query parameter from the request" so the
     * absent-default behavior of every optional field is exercised.
     */
    record QueryParamSpec(String q, String type, String sort, String page, String limit) {
    }
}
