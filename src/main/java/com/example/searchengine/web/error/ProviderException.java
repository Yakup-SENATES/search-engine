package com.example.searchengine.web.error;

/**
 * Web-layer exception representing an upstream provider failure that has propagated
 * to a request handler.
 *
 * <p>Provider failures are normally swallowed inside the adapter (REQ 2.4, 2.6, 3.6,
 * 21.3) and never reach the HTTP layer; this exception exists so that any path
 * which does surface a provider error can be translated into HTTP 502 with
 * {@code error.code = PROVIDER_ERROR} by {@link GlobalExceptionHandler} (REQ 14.4).
 */
public class ProviderException extends RuntimeException {

    public ProviderException(String message) {
        super(message);
    }

    public ProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
