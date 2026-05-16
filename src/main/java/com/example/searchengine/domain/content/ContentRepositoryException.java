package com.example.searchengine.domain.content;

/**
 * Domain exception wrapping infrastructure-level data access failures.
 *
 * <p>Thrown by {@link ContentRepository} implementations when the underlying
 * persistence layer encounters an unrecoverable error. The web layer's
 * {@code GlobalExceptionHandler} translates this into an HTTP 503 response
 * (REQ 14.3).</p>
 */
public class ContentRepositoryException extends RuntimeException {

    public ContentRepositoryException(String message, Throwable cause) {
        super(message, cause);
    }

    public ContentRepositoryException(String message) {
        super(message);
    }
}
