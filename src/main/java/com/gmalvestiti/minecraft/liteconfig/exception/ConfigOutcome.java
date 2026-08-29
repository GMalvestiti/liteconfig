package com.gmalvestiti.minecraft.liteconfig.exception;

import com.gmalvestiti.minecraft.liteconfig.api.FailurePolicy;
import com.gmalvestiti.minecraft.liteconfig.api.spi.Violation;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Result of an operation wrapped by {@link ConfigExceptionHandler}.
 *
 * <p>A completed outcome has no failure and may contain a produced value. A degraded outcome has
 * a failure and no produced value. Under {@link FailurePolicy#STRICT}, failures throw and you
 * usually never see a degraded value.
 *
 * <p>Typical use:
 *
 * <pre>{@code
 * ConfigOutcome<MyConfig> read = handler.onRead(MyConfig.class, storage::read);
 * MyConfig config = read.valueOr(MyConfig::new);
 *
 * ConfigOutcome<Void> write = handler.onWrite(() -> storage.write(config));
 *
 * if (write.degraded()) {
 *     logger.warn("Write failed: {}", write.failure().orElseThrow().getMessage());
 * }
 * }</pre>
 *
 * <p>Use {@link #completed()} / {@link #degraded()} to test status. Do not infer status from
 * {@link #value()} being empty, because a successful operation may still produce no value.
 *
 * @param produced completed operation value; may be {@code null}
 * @param cause swallowed fallback failure; {@code null} for completed outcomes
 * @param <V> produced value type (or {@link Void} for no value)
 */
public record ConfigOutcome<V>(V produced, LiteConfigException cause) {

    private static final ConfigOutcome<?> EMPTY_COMPLETED = new ConfigOutcome<>(null, null);

    public ConfigOutcome {
        if (produced != null && cause != null) {
            throw new IllegalArgumentException("An outcome cannot contain both a value and a failure");
        }
    }

    /** Creates a completed outcome. */
    public static <V> ConfigOutcome<V> completed(V value) {
        return value == null ? emptyCompleted() : new ConfigOutcome<>(value, null);
    }

    /** Creates a degraded outcome from a swallowed fallback failure. */
    public static <V> ConfigOutcome<V> degraded(LiteConfigException failure) {
        return new ConfigOutcome<>(null, Objects.requireNonNull(failure, "failure"));
    }

    /** {@code true} when the guarded operation finished successfully. */
    public boolean completed() {
        return cause == null;
    }

    /** {@code true} when fallback swallowed an expected failure. */
    public boolean degraded() {
        return !completed();
    }

    /** Produced value, if any. */
    public Optional<V> value() {
        return Optional.ofNullable(produced);
    }

    /** Swallowed failure for degraded outcomes. */
    public Optional<LiteConfigException> failure() {
        return Optional.ofNullable(cause);
    }

    /** Produced value, or {@code fallback.get()} when absent. */
    public V valueOr(Supplier<V> fallback) {
        return produced != null ? produced : Objects.requireNonNull(fallback, "fallback").get();
    }

    /** Validation violations carried by the degraded failure, when present. */
    public List<Violation> violations() {
        return cause == null ? List.of() : cause.violations();
    }

    @SuppressWarnings("unchecked")
    private static <V> ConfigOutcome<V> emptyCompleted() {
        return (ConfigOutcome<V>) EMPTY_COMPLETED;
    }
}
