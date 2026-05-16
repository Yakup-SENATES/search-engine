package com.example.searchengine.web.dashboard;

import com.example.searchengine.application.search.SearchResult;
import com.example.searchengine.application.search.SearchService;
import com.example.searchengine.domain.content.Content;
import com.example.searchengine.domain.content.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DashboardController} tolerant parsing and empty-state
 * behavior. Invokes the controller method directly with a mocked
 * {@link SearchService} and a Spring {@link ConcurrentModel}, so no servlet
 * container or {@code @WebMvcTest} context is required.
 *
 * <p>Validates Requirements 10.5, 10.7, 10.8.</p>
 */
@ExtendWith(MockitoExtension.class)
class DashboardControllerTest {

    private static final String VIEW_NAME = "dashboard";

    @Mock
    private SearchService searchService;

    private DashboardController controller;
    private Model model;

    @BeforeEach
    void setUp() {
        controller = new DashboardController(searchService);
        model = new ConcurrentModel();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static SearchResult emptyResult() {
        return new SearchResult(List.of(), 0L, 1, DashboardController.DEFAULT_LIMIT);
    }

    private static SearchResult resultWith(Content... items) {
        return new SearchResult(List.of(items), items.length, 1, DashboardController.DEFAULT_LIMIT);
    }

    private static Content sampleContent(String title, ContentType type, double finalScore) {
        Instant now = Instant.parse("2024-08-01T12:00:00Z");
        return new Content(
                UUID.randomUUID(),
                "test-provider",
                "ext-" + title,
                title,
                "description",
                type,
                100L, 10L, 5, 2L,
                null,
                List.of(),
                now,
                finalScore,
                0.0,
                0.0,
                now,
                now
        );
    }

    @SuppressWarnings("unchecked")
    private static List<DashboardRow> rows(Model model) {
        return (List<DashboardRow>) model.getAttribute("rows");
    }

    private static IgnoredParamNotice notice(Model model) {
        return (IgnoredParamNotice) model.getAttribute("notice");
    }

    // ------------------------------------------------------------------
    // REQ 10.5 — invalid sort falls back to default + ignored notice
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Tolerant sort parsing (REQ 10.5)")
    class TolerantSort {

        @Test
        @DisplayName("Invalid sort falls back to 'score' and surfaces an ignored notice mentioning 'sort'")
        void invalidSort_fallsBackToDefault_andRaisesNotice() {
            when(searchService.listTop(eq("score"), eq(null), eq(DashboardController.DEFAULT_LIMIT)))
                    .thenReturn(emptyResult());

            String view = controller.dashboard("invalid", null, model);

            assertThat(view).isEqualTo(VIEW_NAME);
            // Default ordering: listTop invoked with the canonical "score" sort.
            verify(searchService).listTop("score", null, DashboardController.DEFAULT_LIMIT);

            IgnoredParamNotice notice = notice(model);
            assertThat(notice).isNotNull();
            assertThat(notice.hasMessages()).isTrue();
            assertThat(notice.messages())
                    .anySatisfy(msg -> assertThat(msg).containsIgnoringCase("sort"));
            // Original (rejected) value is echoed back to the user.
            assertThat(notice.messages())
                    .anySatisfy(msg -> assertThat(msg).contains("invalid"));
        }

        @Test
        @DisplayName("Null sort uses default 'score' without raising a notice")
        void nullSort_usesDefault_noNotice() {
            when(searchService.listTop(eq("score"), eq(null), eq(DashboardController.DEFAULT_LIMIT)))
                    .thenReturn(emptyResult());

            controller.dashboard(null, null, model);

            verify(searchService).listTop("score", null, DashboardController.DEFAULT_LIMIT);
            assertThat(notice(model).hasMessages()).isFalse();
        }

        @Test
        @DisplayName("Blank sort uses default 'score' without raising a notice")
        void blankSort_usesDefault_noNotice() {
            when(searchService.listTop(eq("score"), eq(null), eq(DashboardController.DEFAULT_LIMIT)))
                    .thenReturn(emptyResult());

            controller.dashboard("   ", null, model);

            verify(searchService).listTop("score", null, DashboardController.DEFAULT_LIMIT);
            assertThat(notice(model).hasMessages()).isFalse();
        }
    }

    // ------------------------------------------------------------------
    // REQ 10.7 — invalid type falls back to no filter + ignored notice
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Tolerant type parsing (REQ 10.7)")
    class TolerantType {

        @Test
        @DisplayName("Invalid type falls back to no filter (null) and surfaces an ignored notice mentioning 'type'")
        void invalidType_fallsBackToNoFilter_andRaisesNotice() {
            when(searchService.listTop(eq("score"), eq(null), eq(DashboardController.DEFAULT_LIMIT)))
                    .thenReturn(emptyResult());

            String view = controller.dashboard(null, "invalid", model);

            assertThat(view).isEqualTo(VIEW_NAME);
            // No filter applied: listTop invoked with type == null.
            ArgumentCaptor<String> sortCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> typeCaptor = ArgumentCaptor.forClass(String.class);
            verify(searchService).listTop(sortCaptor.capture(), typeCaptor.capture(), eq(DashboardController.DEFAULT_LIMIT));
            assertThat(sortCaptor.getValue()).isEqualTo("score");
            assertThat(typeCaptor.getValue()).isNull();

            IgnoredParamNotice notice = notice(model);
            assertThat(notice).isNotNull();
            assertThat(notice.hasMessages()).isTrue();
            assertThat(notice.messages())
                    .anySatisfy(msg -> assertThat(msg).containsIgnoringCase("type"));
            assertThat(notice.messages())
                    .anySatisfy(msg -> assertThat(msg).contains("invalid"));
        }

        @Test
        @DisplayName("Valid lowercase type 'video' is forwarded to the service and no notice is raised")
        void validVideoType_forwarded_noNotice() {
            when(searchService.listTop(eq("score"), eq("video"), eq(DashboardController.DEFAULT_LIMIT)))
                    .thenReturn(emptyResult());

            controller.dashboard(null, "video", model);

            verify(searchService).listTop("score", "video", DashboardController.DEFAULT_LIMIT);
            assertThat(notice(model).hasMessages()).isFalse();
        }
    }

    // ------------------------------------------------------------------
    // REQ 10.4 / 10.6 — valid parameters: normal ordering, no notice
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Valid sort and type produce no notice and forward values to the service")
    void validSortAndType_noNotice_normalOrdering() {
        when(searchService.listTop(eq("popularity"), eq("text"), eq(DashboardController.DEFAULT_LIMIT)))
                .thenReturn(resultWith(sampleContent("Hello", ContentType.TEXT, 12.5)));

        String view = controller.dashboard("popularity", "text", model);

        assertThat(view).isEqualTo(VIEW_NAME);
        verify(searchService).listTop("popularity", "text", DashboardController.DEFAULT_LIMIT);

        assertThat(notice(model).hasMessages()).isFalse();
        assertThat(notice(model).messages()).isEmpty();
        assertThat(rows(model)).hasSize(1);
        assertThat(model.getAttribute("empty")).isEqualTo(false);
    }

    // ------------------------------------------------------------------
    // REQ 10.8 — empty result renders the empty-state message
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Empty SearchResult sets model attribute 'empty' to true and 'rows' to an empty list")
    void emptyResult_setsEmptyAttributeTrue() {
        when(searchService.listTop(eq("score"), eq(null), eq(DashboardController.DEFAULT_LIMIT)))
                .thenReturn(emptyResult());

        String view = controller.dashboard(null, null, model);

        assertThat(view).isEqualTo(VIEW_NAME);
        assertThat(rows(model)).isNotNull().isEmpty();
        assertThat(model.getAttribute("empty")).isEqualTo(true);
    }

    // ------------------------------------------------------------------
    // REQ 10.5 + 10.7 — both invalid produces two messages in notice
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Both invalid sort and invalid type produce both messages in the notice")
    void bothInvalid_bothMessagesPresent() {
        when(searchService.listTop(eq("score"), eq(null), eq(DashboardController.DEFAULT_LIMIT)))
                .thenReturn(emptyResult());

        controller.dashboard("nope", "alsonope", model);

        verify(searchService).listTop("score", null, DashboardController.DEFAULT_LIMIT);

        IgnoredParamNotice notice = notice(model);
        assertThat(notice.hasMessages()).isTrue();
        assertThat(notice.messages()).hasSize(2);
        assertThat(notice.messages())
                .anySatisfy(msg -> assertThat(msg).containsIgnoringCase("sort"))
                .anySatisfy(msg -> assertThat(msg).containsIgnoringCase("type"));
    }

    // ------------------------------------------------------------------
    // Sanity: rows are populated from SearchResult items in order, and the
    // 'empty' flag is false when there are rows. Type-string casing is not
    // asserted here — that's covered elsewhere by DashboardRow's own tests.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Rows preserve the order returned by SearchService and the empty flag is false")
    void rows_preserveOrder_andEmptyFlagFalse() {
        Content first = sampleContent("First", ContentType.VIDEO, 99.0);
        Content second = sampleContent("Second", ContentType.TEXT, 1.0);
        when(searchService.listTop(eq("score"), eq(null), eq(DashboardController.DEFAULT_LIMIT)))
                .thenReturn(resultWith(first, second));

        controller.dashboard(null, null, model);

        List<DashboardRow> rendered = rows(model);
        assertThat(rendered).hasSize(2);
        assertThat(rendered.get(0).title()).isEqualTo("First");
        assertThat(rendered.get(0).score()).isEqualTo(99.0);
        assertThat(rendered.get(1).title()).isEqualTo("Second");
        assertThat(rendered.get(1).score()).isEqualTo(1.0);
        assertThat(model.getAttribute("empty")).isEqualTo(false);
    }
}
