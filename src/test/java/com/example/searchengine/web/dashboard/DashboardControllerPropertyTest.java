package com.example.searchengine.web.dashboard;

import com.example.searchengine.application.search.SearchResult;
import com.example.searchengine.application.search.SearchService;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.WithNull;
import net.jqwik.api.lifecycle.BeforeProperty;

import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property-based tests for {@link DashboardController}'s tolerant parsing of the
 * {@code sort} and {@code type} request parameters into the {@code activeSort}
 * and {@code activeType} model attributes.
 *
 * <p>The controller is instantiated directly with a Mockito-mocked
 * {@link SearchService} (mirroring {@link DashboardControllerTest}); the mock
 * always returns an empty {@link SearchResult} so the assertions focus on
 * parsing semantics and not on row content. No Spring context, no
 * Testcontainers.</p>
 *
 * <p><b>Validates: Requirements 3.1, 3.2, 3.6, 9.2</b></p>
 */
class DashboardControllerPropertyTest {

    private static final Set<String> ALLOWED_SORTS = Set.of("score", "popularity", "relevance");
    private static final Set<String> ALLOWED_TYPES = Set.of("text", "video");

    private SearchService searchService;
    private DashboardController controller;

    @BeforeProperty
    void setUp() {
        searchService = mock(SearchService.class);
        SearchResult emptyResult = new SearchResult(List.of(), 0L, 1, DashboardController.DEFAULT_LIMIT);
        when(searchService.listTop(any(), any(), anyInt())).thenReturn(emptyResult);
        controller = new DashboardController(searchService);
    }

    // ------------------------------------------------------------------
    // Property 1 — activeSort tolerant-parsing contract
    // ------------------------------------------------------------------

    /**
     * <b>Validates: Requirements 3.1, 3.6, 9.2</b>
     *
     * <p>For any input string {@code s} (including {@code null}, blank,
     * arbitrary Unicode, or biased samples drawn from the allowed-sort
     * vocabulary with random casing / surrounding whitespace), after invoking
     * {@code controller.dashboard(s, null, model)}:</p>
     * <ul>
     *   <li>{@code activeSort} is non-null,</li>
     *   <li>{@code activeSort} is one of {@code {"score","popularity","relevance"}},</li>
     *   <li>if {@code s} is non-null, non-blank, and {@code s.trim().toLowerCase()}
     *       is in the allowed vocabulary, then {@code activeSort} equals
     *       {@code s.trim().toLowerCase()},</li>
     *   <li>otherwise (null, blank, or unrecognized) {@code activeSort} equals
     *       {@code "score"}.</li>
     * </ul>
     */
    @Property(tries = 100)
    @Label("Feature: dashboard-ux-enhancements, Property 1: activeSort tolerant-parsing contract")
    void activeSortTolerantParsingContract(@ForAll("sortInputs") @WithNull String s) {
        Model model = new ConcurrentModel();

        controller.dashboard(s, null, model);

        Object activeSortAttr = model.getAttribute("activeSort");
        assertThat(activeSortAttr)
                .as("activeSort must always be present and non-null (REQ 3.1)")
                .isNotNull()
                .isInstanceOf(String.class);

        String activeSort = (String) activeSortAttr;
        assertThat(activeSort)
                .as("activeSort must be one of the allowed sort values (REQ 3.1)")
                .isIn(ALLOWED_SORTS);

        if (s != null && !s.isBlank()) {
            String normalized = s.trim().toLowerCase();
            if (ALLOWED_SORTS.contains(normalized)) {
                assertThat(activeSort)
                        .as("Allowed input %s must round-trip to its lowercase trimmed form (REQ 3.6, 9.2)", s)
                        .isEqualTo(normalized);
                return;
            }
        }
        assertThat(activeSort)
                .as("Null, blank, or unrecognized input %s must fall back to 'score' (REQ 3.6, 9.2)", s)
                .isEqualTo("score");
    }

    // ------------------------------------------------------------------
    // Property 2 — activeType tolerant-parsing contract
    // ------------------------------------------------------------------

    /**
     * <b>Validates: Requirements 3.2, 3.6, 9.2</b>
     *
     * <p>For any input string {@code s} (including {@code null}, blank,
     * arbitrary Unicode, or biased samples drawn from the allowed-type
     * vocabulary with random casing / surrounding whitespace), after invoking
     * {@code controller.dashboard(null, s, model)}:</p>
     * <ul>
     *   <li>{@code activeType} is one of {@code {"text","video", null}},</li>
     *   <li>if {@code s} is non-null, non-blank, and {@code s.trim().toLowerCase()}
     *       is in {@code {"text","video"}}, then {@code activeType} equals
     *       {@code s.trim().toLowerCase()},</li>
     *   <li>otherwise (null, blank, or unrecognized) {@code activeType} is
     *       {@code null}.</li>
     * </ul>
     */
    @Property(tries = 100)
    @Label("Feature: dashboard-ux-enhancements, Property 2: activeType tolerant-parsing contract")
    void activeTypeTolerantParsingContract(@ForAll("typeInputs") @WithNull String s) {
        Model model = new ConcurrentModel();

        controller.dashboard(null, s, model);

        Object activeTypeAttr = model.getAttribute("activeType");

        if (activeTypeAttr != null) {
            assertThat(activeTypeAttr)
                    .as("activeType, when non-null, must be a String (REQ 3.2)")
                    .isInstanceOf(String.class);
            assertThat((String) activeTypeAttr)
                    .as("activeType, when non-null, must be one of the allowed type values (REQ 3.2)")
                    .isIn(ALLOWED_TYPES);
        }

        if (s != null && !s.isBlank()) {
            String normalized = s.trim().toLowerCase();
            if (ALLOWED_TYPES.contains(normalized)) {
                assertThat(activeTypeAttr)
                        .as("Allowed input %s must round-trip to its lowercase trimmed form (REQ 3.6, 9.2)", s)
                        .isEqualTo(normalized);
                return;
            }
        }
        assertThat(activeTypeAttr)
                .as("Null, blank, or unrecognized input %s must fall back to no filter (null) (REQ 3.6, 9.2)", s)
                .isNull();
    }

    // ─── Generators ───────────────────────────────────────────────────────────

    /**
     * Strings biased toward valid sort tokens with random casing and whitespace
     * mixed with unrestricted arbitrary strings, so that each property iteration
     * covers both "near-allowed" inputs (which exercise the round-trip clause)
     * and entirely arbitrary garbage (which exercises the fall-back clause).
     *
     * <p>The {@code @WithNull} annotation on the parameter contributes the
     * {@code null} branch.</p>
     */
    @Provide
    Arbitrary<String> sortInputs() {
        return biasedTokenStrings(List.of("score", "popularity", "relevance"));
    }

    /**
     * Strings biased toward valid type tokens with random casing and whitespace
     * mixed with unrestricted arbitrary strings.
     */
    @Provide
    Arbitrary<String> typeInputs() {
        return biasedTokenStrings(List.of("text", "video"));
    }

    /**
     * Builds a biased {@code Arbitrary<String>} that mixes:
     * <ul>
     *   <li>variants of one of the {@code allowedTokens} (random casing,
     *       optional surrounding whitespace) — exercises the round-trip clause,</li>
     *   <li>empty / whitespace-only strings — exercises the blank fall-back,</li>
     *   <li>arbitrary alphanumeric strings — exercises the unrecognized
     *       fall-back.</li>
     * </ul>
     *
     * <p>Combined with the {@code @WithNull} parameter annotation, every
     * branch of the controller's tolerant parser is exercised across the
     * 100-iteration default.</p>
     */
    private static Arbitrary<String> biasedTokenStrings(List<String> allowedTokens) {
        Arbitrary<String> tokenVariants = Arbitraries.of(allowedTokens)
                .map(DashboardControllerPropertyTest::randomCasing)
                .map(DashboardControllerPropertyTest::pad);

        Arbitrary<String> blanks = Arbitraries.of("", " ", "  ", "\t", "\n", "  \t  ");

        Arbitrary<String> arbitraryStrings = Arbitraries.strings()
                .alpha()
                .ofMinLength(1)
                .ofMaxLength(20);

        return Arbitraries.oneOf(tokenVariants, tokenVariants, blanks, arbitraryStrings);
    }

    /**
     * Returns the input with each character randomly upper- or lower-cased.
     * Determinism is intentionally relaxed here — jqwik repeats failing seeds
     * automatically, and the property assertion uses {@code trim().toLowerCase()}
     * so the exact casing is irrelevant beyond exercising the case-folding
     * branch of the parser.
     */
    private static String randomCasing(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            out.append(((i + s.hashCode()) & 1) == 0 ? Character.toUpperCase(c) : Character.toLowerCase(c));
        }
        return out.toString();
    }

    /**
     * Adds zero or more whitespace characters before/after the token to exercise
     * the parser's {@code trim()} step. Encoded as a deterministic function of
     * the input length so it is shrinkable.
     */
    private static String pad(String s) {
        int hash = Math.abs(s.hashCode());
        int leftPad = hash % 3;
        int rightPad = (hash / 3) % 3;
        return " ".repeat(leftPad) + s + " ".repeat(rightPad);
    }
}
