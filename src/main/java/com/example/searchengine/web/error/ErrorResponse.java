package com.example.searchengine.web.error;

/**
 * Standardized error envelope returned by every failure path of the public HTTP API.
 *
 * <p>The wire shape is:
 * <pre>{@code
 * { "error": { "code": "<CODE>", "message": "<msg>" } }
 * }</pre>
 *
 * <p>Every controller exception is translated into one of these by
 * {@link GlobalExceptionHandler} (REQ 14.1). The {@code code} field is one of the
 * fixed identifiers documented in the design (e.g. {@code INVALID_QUERY},
 * {@code PAYLOAD_TOO_LARGE}, {@code DATABASE_UNAVAILABLE}, {@code PROVIDER_ERROR},
 * {@code INTERNAL_ERROR}).
 *
 * @param error the inner error payload, never {@code null}
 */
public record ErrorResponse(Error error) {

    /**
     * Convenience factory that builds a complete envelope from the {@code (code, message)}
     * pair the handlers carry around.
     */
    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(new Error(code, message));
    }

    /**
     * Inner payload of the error envelope.
     *
     * @param code    short, machine-readable error identifier (REQ 14.1–14.5)
     * @param message human-readable description; sanitized via
     *                {@link ErrorMessageSanitizer} before serialization (REQ 18.5)
     */
    public record Error(String code, String message) {
    }
}
