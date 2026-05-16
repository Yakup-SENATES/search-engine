package com.example.searchengine.domain.content;

/**
 * Supported content types. Provider-specific values (e.g. "article") are
 * normalized via {@link #fromProviderValue(String)}.
 *
 * <p>REQ 4.5, REQ 3.3</p>
 */
public enum ContentType {
    VIDEO, TEXT;

    /**
     * Maps a raw provider type string to a {@code ContentType}.
     * Handles case-insensitive matching and the {@code article → TEXT} alias.
     *
     * @param raw the provider-supplied type value
     * @return the corresponding {@code ContentType}
     * @throws IllegalArgumentException if {@code raw} is null or unrecognized
     */
    public static ContentType fromProviderValue(String raw) {
        if (raw == null) throw new IllegalArgumentException("type is null");
        return switch (raw.trim().toLowerCase()) {
            case "video" -> VIDEO;
            case "text", "article" -> TEXT;
            default -> throw new IllegalArgumentException("unknown type: " + raw);
        };
    }
}
