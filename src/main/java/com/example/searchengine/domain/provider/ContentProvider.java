package com.example.searchengine.domain.provider;

import java.util.List;

/**
 * Strategy interface for external content sources.
 * Each concrete provider adapter implements this interface and is registered
 * as a Spring bean in the infrastructure layer.
 *
 * <p>Pure Java — no framework imports. REQ 1.1, REQ 22.2</p>
 */
public interface ContentProvider {

    /**
     * Stable, unique identifier of this provider (e.g. "provider1-json").
     * Used as the {@code provider} field in the domain {@code Content} aggregate
     * and as part of the duplicate-prevention composite key.
     *
     * @return non-null, non-blank provider name
     */
    String name();

    /**
     * Fetches a snapshot of raw content from the underlying source.
     * Implementations MUST NOT throw checked exceptions on transport-level
     * failures — they SHALL log and return an empty list (REQ 2.4, 2.6, 3.6).
     *
     * @return immutable list of raw content items, never null
     */
    List<RawContent> fetch();
}
