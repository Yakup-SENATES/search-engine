package com.example.searchengine.infrastructure.config;

import java.util.Objects;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;

/**
 * Wraps another {@link PropertySource} and intercepts every {@link #getProperty(String)}
 * call: if the requested key matches a configured secret pattern (e.g.
 * {@code password}, {@code secret}, {@code token}, {@code api-key}), the wrapper
 * returns the literal {@link SecretKeyMatcher#REDACTION} marker instead of the
 * underlying value (REQ 18.5).
 *
 * <p>This is the property-source half of the secret-redaction strategy described
 * in {@code design.md} § Secret redaction. Code that reads a property
 * <em>for log output</em> — e.g. a startup banner, an actuator info contributor,
 * or any diagnostic that calls {@code env.getProperty("spring.datasource.password")}
 * — receives the redaction marker. Spring's binders that drive the actual
 * datasource and provider clients still resolve the real value, because they
 * iterate the underlying property sources directly rather than going through
 * this wrapper's {@link #getProperty} method.
 *
 * <p>The wrapper is itself an {@link EnumerablePropertySource} when its delegate
 * is enumerable, so {@code Environment.getPropertyNames()} continues to list
 * every key the delegate exposes; only the resolved <em>values</em> for secret
 * keys are intercepted.
 *
 * <p>The wrapper does not modify the delegate. Multiple wrappers may share a
 * single underlying source.
 */
public class SecretRedactingPropertySource extends EnumerablePropertySource<PropertySource<?>> {

    /** Default name applied when no explicit name is supplied. */
    public static final String DEFAULT_NAME = "secretRedactingPropertySource";

    private final SecretKeyMatcher matcher;

    /**
     * Convenience constructor wrapping {@code delegate} with the default
     * {@link SecretKeyMatcher} patterns and the default wrapper name.
     */
    public SecretRedactingPropertySource(PropertySource<?> delegate) {
        this(DEFAULT_NAME, delegate, new SecretKeyMatcher());
    }

    /**
     * Wraps {@code delegate} under {@code name} using the supplied matcher.
     *
     * @param name     non-blank Spring property-source name
     * @param delegate the underlying property source whose values will be
     *                 intercepted; must be non-null
     * @param matcher  decides which keys are secret; must be non-null
     */
    public SecretRedactingPropertySource(
            String name, PropertySource<?> delegate, SecretKeyMatcher matcher) {
        super(name, Objects.requireNonNull(delegate, "delegate"));
        this.matcher = Objects.requireNonNull(matcher, "matcher");
    }

    /**
     * Returns {@link SecretKeyMatcher#REDACTION} when {@code name} matches a
     * configured secret pattern; otherwise delegates to the wrapped source.
     */
    @Override
    public Object getProperty(String name) {
        Object value = getSource().getProperty(name);
        if (value == null) {
            return null;
        }
        if (matcher.isSecret(name)) {
            return SecretKeyMatcher.REDACTION;
        }
        return value;
    }

    /**
     * Returns the property names exposed by the underlying source if it is an
     * {@link EnumerablePropertySource}; otherwise an empty array. Names are not
     * filtered — only values are redacted by {@link #getProperty(String)}.
     */
    @Override
    public String[] getPropertyNames() {
        PropertySource<?> source = getSource();
        if (source instanceof EnumerablePropertySource<?> enumerable) {
            return enumerable.getPropertyNames();
        }
        return new String[0];
    }

    /** Visible for tests; returns the matcher used to classify keys. */
    public SecretKeyMatcher matcher() {
        return matcher;
    }

    /** Visible for tests; returns the wrapped delegate. */
    public PropertySource<?> delegate() {
        return getSource();
    }
}
