package com.example.searchengine.domain.content;

/**
 * Outcome of a {@link ContentRepository#upsert(Content)} operation.
 *
 * <p>REQ 5.2, 5.7</p>
 */
public enum UpsertOutcome {
    /** A new row was inserted (the {@code (provider, externalId)} pair did not exist). */
    INSERTED,
    /** An existing row was updated (the {@code (provider, externalId)} pair already existed). */
    UPDATED
}
