package com.gmalvestiti.minecraft.liteconfig.api.spi;

import com.gmalvestiti.minecraft.liteconfig.exception.ConfigError;

import java.util.Objects;

/**
 * One failed validation rule, as a value object.
 *
 * <p>Reach for {@link #of(String, String)} first — most rules need only an id and a message:
 *
 * <pre>{@code
 * @Override
 * public void validate(List<Violation> violations) {
 *     if (maxItems < 1 || maxItems > 64) {
 *         violations.add(Violation.of("max-items.range", "maxItems must be 1..64, was " + maxItems));
 *     }
 * }
 * }</pre>
 *
 * <p>Implement the interface yourself when a rule carries extra data callers should branch on;
 * a record with the two accessors plus your own components is usually all it takes:
 *
 * <pre>{@code
 * record OutOfRange(String id, String message, int actual) implements Violation { }
 * }</pre>
 *
 * <p>The runner gathers every violation under the root and folds the messages into a single
 * {@link ConfigError#VALIDATION_FAILED} failure, which the read or update policy may throw or
 * degrade. The objects survive either way: on {@code LiteConfigException.violations()} when it
 * throws, and in the {@code UpdateResult} returned when a fallback policy discards the candidate.
 */
public interface Violation {

    /**
     * A violation that carries nothing but an id and a message.
     *
     * @param id stable identifier for the rule; must not be {@code null} or blank
     * @param message what went wrong and how to fix it; must not be {@code null}
     * @return an immutable violation; never {@code null}
     * @throws NullPointerException if {@code id} or {@code message} is {@code null}
     * @throws IllegalArgumentException if {@code id} is blank
     */
    static Violation of(String id, String message) {
        return new Default(id, message);
    }

    /**
     * Identifies the violated rule.
     *
     * <p>Keep it stable across releases; callers branch on it rather than parsing messages.
     *
     * @return non-null, non-blank id
     */
    String id();

    /**
     * Describes what went wrong, for logs and exception messages.
     *
     * @return non-null message with enough context to fix the offending value
     */
    String message();

    /**
     * What {@link #of(String, String)} hands back.
     *
     * <p>Public so callers can pattern-match on it; go through the factory to create one.
     * The constructor rejects up front what the runner would otherwise report later as a
     * validator defect.
     *
     * @param id stable identifier for the rule; never {@code null} or blank
     * @param message the rendered message; never {@code null}
     */
    record Default(String id, String message) implements Violation {

        /**
         * @throws NullPointerException if {@code id} or {@code message} is {@code null}
         * @throws IllegalArgumentException if {@code id} is blank
         */
        public Default {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(message, "message");
            if (id.isBlank()) {
                throw new IllegalArgumentException("violation id must not be blank");
            }
        }
    }
}
