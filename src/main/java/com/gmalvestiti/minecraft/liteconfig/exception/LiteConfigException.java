package com.gmalvestiti.minecraft.liteconfig.exception;

import com.gmalvestiti.minecraft.liteconfig.api.spi.Violation;

import java.io.Serial;
import java.util.List;
import java.util.Objects;

/**
 * The single exception type LiteConfig throws.
 *
 * <p>One unchecked type for every failure. {@link #error()} carries the stable
 * {@link ConfigError} code, so callers branch on that instead of on subtypes:
 *
 * <pre>{@code
 * try {
 *     holder.load();
 * } catch (LiteConfigException failure) {
 *     switch (failure.error()) {
 *         case MALFORMED_CONFIG_DATA -> recreateConfigFile();
 *         case VALIDATION_FAILED -> failure.violations().forEach(v -> highlightField(v.id()));
 *         default -> throw failure;
 *     }
 * }
 * }</pre>
 *
 * <p>It carries what a caller reads: the code, the message body, the violations behind a
 * rejection, and the cause. Instances come from
 * {@link #of(ConfigError, String, String, List, Throwable)}, which stores the raw body and applies
 * the {@code [modId]} prefix every scoped message carries.
 *
 * <p>Not every failure reaches the caller — the config's policy may degrade or absorb non-defect
 * failures. Defect codes always propagate; see {@link ConfigError#defect()}.
 */
public final class LiteConfigException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final ConfigError error;
    private final transient List<Violation> violations;
    private final String rawMessage;

    /**
     * Creates a scoped failure with the raw message body.
     *
     * <p>Every library failure goes through this factory, so scope formatting and raw-body storage
     * stay in one place. Mods that wrap LiteConfig can use it to report their own failures in the
     * same format.
     *
     * @param error the error code; must not be {@code null}
     * @param modId the reporting mod id; must not be {@code null}
     * @param message the unprefixed message; must not be {@code null}
     * @param violations the violations behind the failure, or {@link List#of()}; must not be
     *     {@code null}
     * @param cause the originating exception; may be {@code null}
     * @return a new failure whose message is prefixed with {@code modId}; never {@code null}
     * @throws NullPointerException if {@code error}, {@code modId}, {@code message}, or
     *     {@code violations} is {@code null}
     */
    public static LiteConfigException of(
        ConfigError error, String modId, String message, List<Violation> violations, Throwable cause) {
        Objects.requireNonNull(modId, "modId");
        Objects.requireNonNull(message, "message");
        return new LiteConfigException(
            error, "[%s] %s".formatted(modId, message), message, violations, cause);
    }

    /**
     * Canonical constructor taking a raw message body.
     *
     * <p>Package-private so the formatting rule cannot be bypassed and stays free to change;
     * {@link #of(ConfigError, String, String, List, Throwable)} is the way in.
     *
     * @param error the error code; must not be {@code null}
     * @param message the raw message body
     * @param violations the violations that produced this failure; must not be {@code null}
     * @param cause the originating exception; may be {@code null}
     * @throws NullPointerException if {@code error} or {@code violations} is {@code null}
     */
    LiteConfigException(
        ConfigError error, String message, List<Violation> violations, Throwable cause) {
        this(error, message, message, violations, cause);
    }

    /**
     * Creates a failure whose thrown message and raw body differ.
     *
     * <p>Used when the message shown to the caller already carries the {@code [modId]} prefix
     * while {@link #rawMessage()} must stay unprefixed.
     *
     * @param error the error code; must not be {@code null}
     * @param message the message reported by {@link #getMessage()}; may carry a scope prefix
     * @param rawMessage the unprefixed message body; must not be {@code null}
     * @param violations the violations that produced this failure; must not be {@code null}
     * @param cause the originating exception; may be {@code null}
     * @throws NullPointerException if {@code error}, {@code rawMessage}, or {@code violations}
     *     is {@code null}
     */
    @Deprecated
    public LiteConfigException(
        ConfigError error, String message, String rawMessage, List<Violation> violations, Throwable cause) {
        super(message, cause);
        this.error = Objects.requireNonNull(error, "error");
        this.rawMessage = Objects.requireNonNull(rawMessage, "rawMessage");
        this.violations = List.copyOf(Objects.requireNonNull(violations, "violations"));
    }

    /**
     * Returns the stable error code for caller decisions.
     *
     * @return the error code; never {@code null}
     */
    public ConfigError error() {
        return error;
    }

    /**
     * Returns whether this failure is a programming defect that no policy may degrade.
     *
     * <p>Delegates to {@link ConfigError#defect()} so the flag cannot drift from the code that
     * defines it.
     *
     * @return {@code true} when the failure must always be logged and propagated
     */
    public boolean defect() {
        return error.defect();
    }

    /**
     * Returns the violations that produced this failure.
     *
     * <p>Populated only for {@link ConfigError#VALIDATION_FAILED}; every other code carries an
     * empty list. Each entry keeps its stable {@link Violation#id()}, so callers can branch per
     * rule instead of parsing {@link #getMessage()}.
     *
     * <p>The list does not survive Java serialization: a deserialized exception reports no
     * violations while {@link #error()} and the message stay intact.
     *
     * @return immutable violation list; never {@code null}, empty when the failure is not a
     *         validation rejection
     */
    public List<Violation> violations() {
        return violations == null ? List.of() : violations;
    }

    /**
     * Returns the raw message body without any scope prefix.
     *
     * @return the raw message; never {@code null}
     */
    public String rawMessage() {
        return rawMessage;
    }
}
