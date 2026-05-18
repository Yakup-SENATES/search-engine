package com.example.searchengine.web.error;

import com.example.searchengine.application.analytics.SearchAnalyticsRecorder;
import com.example.searchengine.domain.content.ContentRepositoryException;
import com.example.searchengine.infrastructure.admin.ClientIpHasher;
import com.example.searchengine.infrastructure.ratelimit.ClientIpResolver;

import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link GlobalExceptionHandler}.
 *
 * <p>One test per row of the mapping table documented on the handler:
 * validation (400 / {@code INVALID_QUERY}), payload-too-large (413 /
 * {@code PAYLOAD_TOO_LARGE}), repository (503 / {@code DATABASE_UNAVAILABLE}),
 * provider (502 / {@code PROVIDER_ERROR}), and the catch-all {@link Throwable}
 * branch (500 / {@code INTERNAL_ERROR}). Each test asserts the HTTP status,
 * the {@code error.code} on the envelope, and — for the 500 branch — that the
 * message is the literal {@value GlobalExceptionHandler#INTERNAL_ERROR_MESSAGE}
 * mandated by REQ 14.5.
 *
 * <p>The handler is constructed directly with an
 * {@link ErrorMessageSanitizer} that has no configured secrets, so the
 * sanitizer is a no-op and assertions can read the raw message produced by
 * the handler. No Spring context, MockMvc, or servlet container is needed.
 *
 * <p>Validates Requirements 14.1, 14.2, 14.3, 14.4, 14.5, 19.5.
 */
class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;
    private MockHttpServletRequest mockRequest;

    @BeforeEach
    void setUp() {
        // No-op sanitizer: empty secret list lets messages flow through unchanged.
        SearchAnalyticsRecorder analyticsRecorder = mock(SearchAnalyticsRecorder.class);
        ClientIpHasher clientIpHasher = new ClientIpHasher();
        ClientIpResolver clientIpResolver = new ClientIpResolver();
        handler = new GlobalExceptionHandler(
                new ErrorMessageSanitizer(List.of()),
                analyticsRecorder,
                clientIpHasher,
                clientIpResolver
        );
        mockRequest = new MockHttpServletRequest();
        mockRequest.setRequestURI("/api/v1/search");
        mockRequest.setParameter("q", "test");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Asserts the standard envelope contract: non-null body, non-null inner
     * {@code error}, and the supplied {@code (status, code)} pair.
     */
    private static void assertEnvelope(ResponseEntity<ErrorResponse> response, HttpStatus status, String code) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error()).isNotNull();
        assertThat(response.getBody().error().code()).isEqualTo(code);
    }

    // ------------------------------------------------------------------
    // 400 — INVALID_QUERY (REQ 14.1, 14.2, 19.5)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("MethodArgumentNotValidException → 400 with code INVALID_QUERY")
    void methodArgumentNotValid_returns400WithInvalidQuery() {
        // Real BindingResult with a FieldError so firstFieldErrorMessage has
        // something to render; MethodParameter is irrelevant for the response
        // body so we mock it.
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "request");
        bindingResult.addError(new FieldError("request", "q", "must not be blank"));
        MethodArgumentNotValidException ex =
                new MethodArgumentNotValidException(mock(MethodParameter.class), bindingResult);

        ResponseEntity<ErrorResponse> response = handler.handleMethodArgumentNotValid(ex);

        assertEnvelope(response, HttpStatus.BAD_REQUEST, "INVALID_QUERY");
    }

    @Test
    @DisplayName("BindException → 400 with code INVALID_QUERY")
    void bindException_returns400WithInvalidQuery() {
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "request");
        bindingResult.addError(new FieldError("request", "limit", "must be >= 1"));
        BindException ex = new BindException(bindingResult);

        ResponseEntity<ErrorResponse> response = handler.handleBind(ex);

        assertEnvelope(response, HttpStatus.BAD_REQUEST, "INVALID_QUERY");
    }

    @Test
    @DisplayName("ConstraintViolationException → 400 with code INVALID_QUERY")
    void constraintViolationException_returns400WithInvalidQuery() {
        // Empty violation set exercises the null/empty branch of
        // firstViolationMessage and avoids depending on a Validator instance.
        ConstraintViolationException ex =
                new ConstraintViolationException("invalid", Collections.emptySet());

        ResponseEntity<ErrorResponse> response = handler.handleConstraintViolation(ex);

        assertEnvelope(response, HttpStatus.BAD_REQUEST, "INVALID_QUERY");
    }

    @Test
    @DisplayName("MissingServletRequestParameterException → 400 with code INVALID_QUERY")
    void missingServletRequestParameterException_returns400WithInvalidQuery() {
        MissingServletRequestParameterException ex =
                new MissingServletRequestParameterException("q", "String");

        ResponseEntity<ErrorResponse> response = handler.handleMissingParam(ex);

        assertEnvelope(response, HttpStatus.BAD_REQUEST, "INVALID_QUERY");
    }

    @Test
    @DisplayName("HttpMessageNotReadableException → 400 with code INVALID_QUERY")
    void httpMessageNotReadableException_returns400WithInvalidQuery() {
        HttpMessageNotReadableException ex =
                new HttpMessageNotReadableException("bad json", new MockHttpInputMessage(new byte[0]));

        ResponseEntity<ErrorResponse> response = handler.handleNotReadable(ex);

        assertEnvelope(response, HttpStatus.BAD_REQUEST, "INVALID_QUERY");
    }

    // ------------------------------------------------------------------
    // 413 — PAYLOAD_TOO_LARGE (REQ 19.5)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("MaxUploadSizeExceededException → 413 with code PAYLOAD_TOO_LARGE")
    void maxUploadSizeExceeded_returns413WithPayloadTooLarge() {
        MaxUploadSizeExceededException ex = new MaxUploadSizeExceededException(8192L);

        ResponseEntity<ErrorResponse> response = handler.handlePayloadTooLarge(ex);

        assertEnvelope(response, HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE");
    }

    // ------------------------------------------------------------------
    // 503 — DATABASE_UNAVAILABLE (REQ 14.3)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("ContentRepositoryException → 503 with code DATABASE_UNAVAILABLE")
    void contentRepositoryException_returns503WithDatabaseUnavailable() {
        ContentRepositoryException ex =
                new ContentRepositoryException("connection refused", new RuntimeException("io"));

        ResponseEntity<ErrorResponse> response = handler.handleRepository(ex, mockRequest);

        assertEnvelope(response, HttpStatus.SERVICE_UNAVAILABLE, "DATABASE_UNAVAILABLE");
    }

    // ------------------------------------------------------------------
    // 502 — PROVIDER_ERROR (REQ 14.4)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("ProviderException → 502 with code PROVIDER_ERROR")
    void providerException_returns502WithProviderError() {
        ProviderException ex = new ProviderException("upstream 500");

        ResponseEntity<ErrorResponse> response = handler.handleProvider(ex, mockRequest);

        assertEnvelope(response, HttpStatus.BAD_GATEWAY, "PROVIDER_ERROR");
    }

    // ------------------------------------------------------------------
    // 500 — INTERNAL_ERROR (REQ 14.5)
    //
    // The catch-all branch must always emit the literal message
    // "Internal server error", regardless of the underlying cause, so that
    // no exception detail leaks into the response body.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Generic RuntimeException → 500 with code INTERNAL_ERROR and literal 'Internal server error' message")
    void runtimeException_returns500WithInternalErrorAndLiteralMessage() {
        // Underlying message intentionally distinctive — if the handler ever
        // started echoing the exception text this assertion would catch it.
        RuntimeException ex = new RuntimeException("npe at com.example.Internals.boom");

        ResponseEntity<ErrorResponse> response = handler.handleThrowable(ex, mockRequest);

        assertEnvelope(response, HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR");
        assertThat(response.getBody().error().message()).isEqualTo("Internal server error");
    }

    @Test
    @DisplayName("Generic Throwable → 500 with code INTERNAL_ERROR and literal 'Internal server error' message")
    void throwable_returns500WithInternalErrorAndLiteralMessage() {
        // Asserting on the broader Throwable type — covers Errors and any
        // checked exception that the @ExceptionHandler(Throwable.class)
        // mapping is meant to absorb.
        Throwable ex = new Throwable("low-level failure");

        ResponseEntity<ErrorResponse> response = handler.handleThrowable(ex, mockRequest);

        assertEnvelope(response, HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR");
        assertThat(response.getBody().error().message()).isEqualTo("Internal server error");
    }
}
