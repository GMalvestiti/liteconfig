package com.gmalvestiti.minecraft.liteconfig.exception;

/**
 * Defines the error codes carried by every {@link LiteConfigException}.
 *
 * <p>Callers branch on these enum values rather than on exception subtypes:
 *
 * <pre>{@code
 * catch (LiteConfigException failure) {
 *     switch (failure.error()) {
 *         case MALFORMED_CONFIG_DATA -> LOGGER.warn("config file was reset");
 *         default -> throw failure;
 *     }
 * }
 * }</pre>
 *
 * <p>This enum is pure reference data: each constant owns a
 * {@link String#format(String, Object...)} {@link #template()} and a {@link #defect()} flag. The
 * template is formatted internally before the finished message reaches an exception.
 *
 * <p>{@link #defect()} separates recoverable runtime failures from mod or library bugs. Defects,
 * including {@link #UNEXPECTED_FAILURE}, are always logged and always propagated; no failure
 * policy may hide them.
 *
 * <p>New constants may be added in any minor release, so always include a {@code default} branch
 * when switching over this enum.
 */
public enum ConfigError {
    /** Signals that a root class is not annotated with {@code @Config}. */
    MISSING_CONFIG_MARKER("Class %s must be annotated with @Config"),
    /** Signals that a {@code @Config} class directly references another {@code @Config} type. */
    CONFIG_REFERENCE_FORBIDDEN("Field %s in %s cannot reference another @Config type"),
    /** Signals that a config class cannot provide defaults through a no-arg constructor. */
    MISSING_DEFAULT_CONSTRUCTOR("Class %s must expose a no-arg constructor"),
    /** Signals that a {@code @Config} name cannot be used as a file name. */
    INVALID_CONFIG_NAME("Config %s declares invalid name '%s'"),
    /** Signals that a config path annotation cannot be resolved safely. */
    INVALID_CONFIG_PATH("Config %s declares invalid path '%s'"),
    /** Signals that an entry name would be mistaken for a structural property path. */
    INVALID_ENTRY_NAME("Field %s in %s declares invalid persisted name '%s': names cannot contain '.'", true),
    /** Signals that two persisted fields resolve to the same file key. */
    DUPLICATE_ENTRY_NAME("Fields %s and %s in %s resolve to the same persisted name '%s'", true),
    /** Signals that two configs resolve to the same file. */
    CONFLICTING_CONFIG_PATH("Config %s resolves to %s, which is already owned by %s"),
    /** Signals that one config registration is requested by more than one mod scope. */
    CONFLICTING_CONFIG_SCOPE("Config %s in %s is already registered for mod '%s', not '%s'", true),
    /** Signals that registration hooks recursively request the registration being created. */
    REENTRANT_CONFIG_REGISTRATION("Config %s was registered recursively while its registration hook was still running", true),
    /** Signals that reflection cannot read or write a config field. */
    REFLECTION_ACCESS("Unable to access field %s in class %s"),
    /** Signals that an immutable field cannot be populated from persisted data. */
    FINAL_CONFIG_FIELD("Persisted field %s in class %s must not be final", true),
    /** Signals that storage cannot read a config file. */
    IO_LOAD_FAILURE("Failed to load config from %s"),
    /** Signals that existing config data cannot be parsed. */
    MALFORMED_CONFIG_DATA("Malformed config data in %s"),
    /** Signals that storage cannot persist a config file. */
    IO_SAVE_FAILURE("Failed to save config to %s"),
    /** Signals that the async worker rejects work during JVM shutdown. */
    CONFIG_WORKER_STOPPED("Config worker is no longer accepting work; the JVM is shutting down"),
    /** Signals that a loaded or updated value violates the root's {@code validate} rules. */
    VALIDATION_FAILED("Validation failed for %s: %s"),
    /** Signals that an update tries to change a field declared {@code @Entry(restart = true)}. */
    RESTART_FIELD_CHANGED("Restart-only fields changed in %s: %s"),
    /** Signals that {@code validate} reports a violation with a blank id. */
    VALIDATOR_PRODUCED_BLANK_ID("Validation for %s produced a violation with a blank id", true),
    /** Signals that {@code validate} reports a null violation entry. */
    VALIDATOR_PRODUCED_NULL_VIOLATION("Validation for %s produced a null violation", true),
    /** Signals that {@code validate} throws instead of reporting violations. */
    VALIDATOR_FAILED("Validation for %s threw an exception: %s", true),
    /** Signals that a {@code ConfigExtension} hook throws. */
    EXTENSION_HOOK_FAILED("Config extension hook '%s' threw an exception: %s", true),
    /** Signals that a config lifecycle listener throws after an event is dispatched. */
    CHANGE_LISTENER_FAILED("Config change listener threw an exception: %s"),
    /** Signals that callbacks keep publishing new callback-producing updates without terminating. */
    CHANGE_LISTENER_REENTRANCY_LIMIT("Config callbacks exceeded the reentrant notification limit of %d", true),
    /** Signals that an {@code @Entry(callback = ...)} method has an invalid declaration. */
    INVALID_ENTRY_ON_SET_CALLBACK("Field %s in %s declares an invalid callback: %s", true),
    /** Signals that a field declares a constraint that no value could satisfy. */
    INVALID_CONSTRAINT("Field %s in %s declares an invalid constraint: %s", true),
    /** Signals that a config file was written by a newer version of the mod. */
    CONFIG_VERSION_TOO_NEW("Config %s was written at version %d, but this build understands up to %d"),
    /** Signals that no migration bridges two consecutive config versions. */
    MISSING_CONFIG_MIGRATION("Config %s has no migration registered from version %d to %d", true),
    /** Signals that a declared migration throws instead of rewriting the data. */
    MIGRATION_FAILED("Migration of %s from version %d to %d threw an exception: %s", true),
    /** Signals that a {@code @Migration} method is declared in a way that cannot be run. */
    INVALID_MIGRATION("Config %s declares an invalid migration: %s", true),
    /** Signals that a versioned config declares a field under the reserved version key. */
    RESERVED_VERSION_KEY("Field %s in %s uses '%s', which a versioned config reserves for itself", true),
    /** Signals that holder construction cannot complete. */
    INITIALIZATION_FAILED("Configuration initialization failed: %s"),
    /** Signals that a required builder argument is null or blank. */
    BUILD_REQUIRED_ARGUMENT("Required builder argument '%s' is missing"),
    /** Signals that a holder implementation refuses an operation it does not support. */
    HOLDER_OPERATION_UNSUPPORTED("The %s config holder does not support '%s'"),
    /** Signals that an operation was attempted through a released holder handle. */
    HOLDER_CLOSED("Config holder for %s is closed"),
    /** Signals that a config task tried to queue more work onto the worker running it. */
    NESTED_CONFIG_OPERATION("Config operation scheduled from inside another config task; nested worker waits can deadlock", true),
    /** Signals that a custom state cloner violates the isolation contract. */
    INVALID_STATE_COPY("State cloner for %s %s", true),
    /** Signals that a raw {@link RuntimeException} escapes internal code. */
    UNEXPECTED_FAILURE("Unexpected failure during %s: %s", true),
    /** Signals that a synced field's type has no wire representation. */
    UNSUPPORTED_SYNC_TYPE("Field %s of type %s cannot be synced: no wire representation is registered for it", true),
    /** Signals that one synced config type was registered from more than one config root. */
    CONFLICTING_SYNC_ROOT("Synced config %s is already registered from another config root", true),
    /** Signals that a config sync payload could not be applied to its registered config. */
    SYNC_APPLY_FAILED("Failed to apply a sync payload for %s: %s");

    private final String template;
    private final boolean defect;

    ConfigError(String template) {
        this(template, false);
    }

    ConfigError(String template, boolean defect) {
        this.template = template;
        this.defect = defect;
    }

    /**
     * Returns the raw format template for this code.
     *
     * <p>The template is unformatted and carries no {@code [modId]} prefix;
     * both are applied internally when the library builds a failure.
     *
     * @return the {@link String#format(String, Object...)} template; never {@code null}
     */
    public String template() {
        return template;
    }

    /**
     * Returns whether the code represents a programming defect.
     *
     * <p>Defects cannot be repaired by falling back to defaults or skipping work, so they
     * are always logged and propagated regardless of the config's failure policy.
     *
     * @return {@code true} when no policy may degrade this error
     */
    public boolean defect() {
        return defect;
    }
}
