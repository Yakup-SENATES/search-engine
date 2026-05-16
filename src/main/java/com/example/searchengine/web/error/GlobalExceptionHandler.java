package com.example.searchengine.web.error;

import com.example.searchengine.domain.content.ContentRepositoryException;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * Single translation point from controller exceptions to the standardized error
 * envelope {@code {"error":{"code":<CODE>,"message":<msg>}}} (REQ 14.1).
 *
 * <p>Mapping table (mirrors {@code design.md} § Global Exception Handling):
 * <table>
 *   <caption>Exception → status mapping</caption>
 *   <tr><th>Exception</th><th>Status</th><th>{@code error.code}</th><th>Requirement</th></tr>
 *   <tr><td>{@code MethodArgumentNotValidException},
 *           {@code ConstraintViolationException},
 *           {@code BindException},
 *           {@code MissingServletRequestParameterException},
 *           {@code HttpMessageNotReadableException}</td>
 *       <td>400</td><td>{@code INVALID_QUERY}</td>
 *       <td>14.1, 14.2, 19.5</td></tr>
 *   <tr><td>{@code MaxUploadSizeExceededException}</td><td>413</td>
 *       <td>{@code PAYLOAD_TOO_LARGE}</td><td>19.5</td></tr>
 *   <tr><td>{@code ContentRepositoryException}</td><td>503</td>
 *       <td>{@code DATABASE_UNAVAILABLE}</td><td>14.3, 21.4</td></tr>
 *   <tr><td>{@code ProviderException}</td><td>502</td>
 *       <td>{@code PROVIDER_ERROR}</td><td>14.4</td></tr>
 *   <tr><td>{@code Throwable}</td><td>500</td>
 *       <td>{@code INTERNAL_ERROR}</td><td>14.5</td></tr>
 * </table>
 *
 * <p>The advice is annotated {@link Order#value() @Order(HIGHEST_PRECEDENCE)} so its
 * specific validation handlers always win over a catch-all advice that may be
 * supplied by Spring Boot or third-party starters; within a single advice Spring
 * still resolves to the most-specific exception type, so the {@code Throwable}
 * fallback only fires when no other handler matches (REQ 14.5).
 *
 * <p>Every emitted message is run through {@link ErrorMessageSanitizer} so that
 * any value listed in the {@code secret.values} configuration property is replaced
 * with a redaction marker before serialization (REQ 18.5). The {@code INTERNAL_ERROR}
 * branch never echoes the underlying exception text — it returns the literal string
 * {@code "Internal server error"} and logs the full stack trace at {@code ERROR}
 * level (REQ 14.5).
 */
@ControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    static final String CODE_INVALID_QUERY = "INVALID_QUERY";
    static final String CODE_PAYLOAD_TOO_LARGE = "PAYLOAD_TOO_LARGE";
    static final String CODE_DATABASE_UNAVAILABLE = "DATABASE_UNAVAILABLE";
    static final String CODE_PROVIDER_ERROR = "PROVIDER_ERROR";
    static final String CODE_INTERNAL_ERROR = "INTERNAL_ERROR";

    /** Literal returned for every {@code 500 INTERNAL_ERROR} response (REQ 14.5). */
    static final String INTERNAL_ERROR_MESSAGE = "Internal server error";

    private final ErrorMessageSanitizer sanitizer;

    public GlobalExceptionHandler(ErrorMessageSanitizer sanitizer) {
        this.sanitizer = sanitizer;
    }

    // ------------------------------------------------------------------------
    // 400 — INVALID_QUERY
    // ------------------------------------------------------------------------

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        return badRequest(firstFieldErrorMessage(ex));
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<ErrorResponse> handleBind(BindException ex) {
        return badRequest(firstFieldErrorMessage(ex));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex) {
        return badRequest(firstViolationMessage(ex));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParam(MissingServletRequestParameterException ex) {
        return badRequest("parameter '" + ex.getParameterName() + "': required");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleNotReadable(HttpMessageNotReadableException ex) {
        return badRequest("malformed request body");
    }

    // ------------------------------------------------------------------------
    // 413 — PAYLOAD_TOO_LARGE  (REQ 19.5)
    // ------------------------------------------------------------------------

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handlePayloadTooLarge(MaxUploadSizeExceededException ex) {
        return build(HttpStatus.PAYLOAD_TOO_LARGE, CODE_PAYLOAD_TOO_LARGE, "Request payload exceeds maximum size");
    }

    // ------------------------------------------------------------------------
    // 503 — DATABASE_UNAVAILABLE  (REQ 14.3, 21.4)
    // ------------------------------------------------------------------------

    @ExceptionHandler(ContentRepositoryException.class)
    public ResponseEntity<ErrorResponse> handleRepository(ContentRepositoryException ex) {
        log.error("repository error: {}", ex.toString(), ex);
        return build(HttpStatus.SERVICE_UNAVAILABLE, CODE_DATABASE_UNAVAILABLE, "Database temporarily unavailable");
    }

    // ------------------------------------------------------------------------
    // 502 — PROVIDER_ERROR  (REQ 14.4)
    // ------------------------------------------------------------------------

    @ExceptionHandler(ProviderException.class)
    public ResponseEntity<ErrorResponse> handleProvider(ProviderException ex) {
        log.error("provider error: {}", ex.toString(), ex);
        return build(HttpStatus.BAD_GATEWAY, CODE_PROVIDER_ERROR, "Upstream provider error");
    }

    // ------------------------------------------------------------------------
    // 500 — INTERNAL_ERROR  (catch-all, REQ 14.5)
    // ------------------------------------------------------------------------

    @ExceptionHandler(Throwable.class)
    public ResponseEntity<ErrorResponse> handleThrowable(Throwable ex) {
        log.error("unhandled exception", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, CODE_INTERNAL_ERROR, INTERNAL_ERROR_MESSAGE);
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    private ResponseEntity<ErrorResponse> badRequest(String message) {
        return build(HttpStatus.BAD_REQUEST, CODE_INVALID_QUERY, message);
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String code, String message) {
        String sanitized = sanitizer.sanitize(message);
        return ResponseEntity.status(status).body(ErrorResponse.of(code, sanitized));
    }

    private static String firstFieldErrorMessage(BindException ex) {
        FieldError fieldError = ex.getBindingResult().getFieldError();
        if (fieldError != null) {
            return "field '" + fieldError.getField() + "': "
                    + (fieldError.getDefaultMessage() == null ? "invalid" : fieldError.getDefaultMessage());
        }
        ObjectError objectError = ex.getBindingResult().getGlobalError();
        if (objectError != null) {
            return objectError.getDefaultMessage() == null ? "invalid request" : objectError.getDefaultMessage();
        }
        return "invalid request";
    }

    private static String firstViolationMessage(ConstraintViolationException ex) {
        if (ex.getConstraintViolations() == null || ex.getConstraintViolations().isEmpty()) {
            return "invalid request";
        }
        ConstraintViolation<?> violation = ex.getConstraintViolations().iterator().next();
        String path = violation.getPropertyPath() == null ? "" : violation.getPropertyPath().toString();
        String field = path.isEmpty() ? "request" : leafOf(path);
        String message = violation.getMessage() == null ? "invalid" : violation.getMessage();
        return "field '" + field + "': " + message;
    }

    private static String leafOf(String propertyPath) {
        int dot = propertyPath.lastIndexOf('.');
        return dot < 0 ? propertyPath : propertyPath.substring(dot + 1);
    }
}
