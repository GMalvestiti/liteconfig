package com.gmalvestiti.minecraft.liteconfig.exception;

import com.gmalvestiti.minecraft.liteconfig.LiteConfigCommon;
import com.gmalvestiti.minecraft.liteconfig.api.spi.Violation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * Carries mod identity through LiteConfig and reports diagnostics under it.
 *
 * <p>The scope is the single source of identity, the factory for every {@link LiteConfigException}
 * whose message must start with {@code [modId]}, and the sink for every message LiteConfig logs.
 * Template filling lives here: this is the only type that holds the arguments a
 * {@link ConfigError#template()} needs. {@link LiteConfigException} stores the raw message body;
 * the scope assembles the final prefixed text for both exceptions and log messages.
 *
 * <p>Messages go under the logger name {@code liteconfig/<modId>} and are formatted as
 * {@code [modId] message}, matching exception message prefixes. Routing and filtering are
 * controlled by the host's logging configuration.
 *
 * @param modId the owning mod id; must not be {@code null}
 * @param logger the SLF4J sink for this mod id; resolved from {@code modId} when omitted
 */
public record ConfigScope(String modId, Logger logger) {

    private static final String UNKNOWN_MOD_ID = "unknown";

    public ConfigScope {
        Objects.requireNonNull(modId, "modId");
        Objects.requireNonNull(logger, "logger");
    }

    /**
     * Creates a scope for one mod id, resolving its logger once.
     *
     * @param modId the owning mod id; must not be {@code null}
     * @throws NullPointerException if {@code modId} is {@code null}
     */
    public ConfigScope(String modId) {
        this(modId, loggerFor(Objects.requireNonNull(modId, "modId")));
    }

    /**
     * Creates the placeholder scope used before a mod id is available.
     *
     * @return a scope whose id is {@code "unknown"}
     */
    public static ConfigScope unknown() {
        return new ConfigScope(UNKNOWN_MOD_ID);
    }

    /**
     * Creates a scoped failure with no cause.
     *
     * @param error the error code; must not be {@code null}
     * @param args values consumed by {@code error}'s message template
     * @return a new exception whose message starts with {@code [modId]}
     * @throws NullPointerException if {@code error} is {@code null}
     */
    public LiteConfigException exception(ConfigError error, Object... args) {
        return failure(error, List.of(), null, args);
    }

    /**
     * Creates a scoped failure that also carries the violations behind it.
     *
     * @param error the error code; must not be {@code null}
     * @param violations the violations that produced the failure; must not be {@code null}
     * @param args values consumed by {@code error}'s message template
     * @return a new exception whose message starts with {@code [modId]}
     * @throws NullPointerException if {@code error} or {@code violations} is {@code null}
     */
    public LiteConfigException exception(ConfigError error, List<Violation> violations, Object... args) {
        return failure(error, violations, null, args);
    }

    /**
     * Creates a scoped failure and preserves the cause.
     *
     * @param error the error code; must not be {@code null}
     * @param cause the originating exception; may be {@code null}
     * @param args values consumed by {@code error}'s message template
     * @return a new exception whose message starts with {@code [modId]}
     * @throws NullPointerException if {@code error} is {@code null}
     */
    public LiteConfigException exception(ConfigError error, Throwable cause, Object... args) {
        return failure(error, List.of(), cause, args);
    }

    /**
     * Fills {@code error}'s template and applies this scope's identity to the result.
     *
     * <p>Delegates to {@link LiteConfigException#of(ConfigError, String, String, java.util.List,
     * Throwable)}, which owns the {@code [modId]} prefix. All other {@code exception} overloads
     * delegate here.
     *
     * @param error the error code supplying the template; must not be {@code null}
     * @param violations the violations that produced the failure; must not be {@code null}
     * @param cause the originating exception; may be {@code null}
     * @param args values consumed by the template's placeholders
     * @return a new exception whose message starts with {@code [modId]}
     * @throws NullPointerException if {@code error} or {@code violations} is {@code null}
     */
    private LiteConfigException failure(
        ConfigError error, List<Violation> violations, Throwable cause, Object... args) {
        String message = error.template().formatted(args);
        return LiteConfigException.of(error, modId, message, violations, cause);
    }

    /**
     * Logs an informational message under this scope.
     *
     * @param message message to record; must not be {@code null}
     */
    public void logInfo(String message) {
        logger.info("[{}] {}", modId, message);
    }

    /**
     * Logs a warning for a failure that did not abort the active operation.
     *
     * @param message message to record; must not be {@code null}
     */
    public void logWarning(String message) {
        logger.warn("[{}] {}", modId, message);
    }

    /**
     * Logs a failure and the cause behind it.
     *
     * @param message human-readable failure context; must not be {@code null}
     * @param throwable originating failure; may be {@code null}
     */
    public void logError(String message, Throwable throwable) {
        logger.error("[{}] {}", modId, message, throwable);
    }

    /**
     * Returns the SLF4J logger for a mod id.
     */
    private static Logger loggerFor(String modId) {
        return LoggerFactory.getLogger(LiteConfigCommon.MOD_ID + "/" + modId);
    }
}
