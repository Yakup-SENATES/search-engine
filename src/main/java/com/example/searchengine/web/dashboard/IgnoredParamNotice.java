package com.example.searchengine.web.dashboard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * View model carrying messages about query parameters that were tolerantly
 * ignored by the dashboard (REQ 10.5, 10.7).
 *
 * <p>Exposes a small Thymeleaf-friendly API:
 * {@link #hasMessages()} for the {@code th:if} guard and {@link #messages()}
 * for iteration.</p>
 *
 * <p>Instances are immutable; use the {@link Builder} to assemble them.</p>
 */
public final class IgnoredParamNotice {

    /** Singleton notice with no messages. */
    public static final IgnoredParamNotice EMPTY = new IgnoredParamNotice(List.of());

    private final List<String> messages;

    private IgnoredParamNotice(List<String> messages) {
        this.messages = Collections.unmodifiableList(new ArrayList<>(messages));
    }

    /** @return {@code true} iff at least one parameter was ignored */
    public boolean hasMessages() {
        return !messages.isEmpty();
    }

    /** @return immutable list of human-readable messages */
    public List<String> messages() {
        return messages;
    }

    /** Mutable builder for assembling an {@link IgnoredParamNotice}. */
    public static final class Builder {
        private final List<String> messages = new ArrayList<>();

        /**
         * Records a message indicating that the given parameter value was
         * ignored and the dashboard fell back to its default.
         *
         * @param paramName    the request parameter name (e.g. {@code "sort"})
         * @param providedValue the value the user supplied (may be {@code null})
         * @return this builder
         */
        public Builder ignored(String paramName, String providedValue) {
            String shown = providedValue == null ? "(null)" : "'" + providedValue + "'";
            messages.add(
                    "Ignored " + paramName + " parameter " + shown + " — falling back to default."
            );
            return this;
        }

        public IgnoredParamNotice build() {
            return messages.isEmpty() ? EMPTY : new IgnoredParamNotice(messages);
        }
    }
}
