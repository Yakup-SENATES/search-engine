package com.example.searchengine.domain.scoring;

import com.example.searchengine.domain.content.ContentType;

/**
 * Returns the type-based multiplier applied to the base score.
 * Pure Java — no framework dependencies.
 *
 * <ul>
 *   <li>VIDEO: 1.5 (REQ 6.4)</li>
 *   <li>TEXT: 1.0 (REQ 6.5)</li>
 * </ul>
 */
public final class TypeMultiplier {

    /**
     * Returns the multiplier for the given content type.
     *
     * @param type the content type, must not be null
     * @return 1.5 for VIDEO, 1.0 for TEXT
     */
    public double of(ContentType type) {
        return switch (type) {
            case VIDEO -> 1.5;
            case TEXT -> 1.0;
        };
    }
}
