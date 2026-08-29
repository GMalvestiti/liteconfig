package com.gmalvestiti.minecraft.liteconfig.api;

import com.gmalvestiti.minecraft.liteconfig.api.spi.Violation;
import com.gmalvestiti.minecraft.liteconfig.exception.LiteConfigException;

import java.util.List;
import java.util.Objects;

/**
 * Reports what happened to one {@code update} or {@code updateAndSave} candidate.
 *
 * <p>An update has exactly two outcomes, so the result names them instead of encoding
 * "accepted" as an empty violation list. {@link #accepted()} gives the yes/no answer and
 * {@link #violations()} the failed rules, empty on {@link Published}:
 *
 * <pre>{@code
 * UpdateResult result = holder.updateAndSave(config -> config.hudScale = 99);
 * if (!result.accepted()) {
 *     result.violations().forEach(v -> LOGGER.warn("{}: {}", v.id(), v.message()));
 * }
 * }</pre>
 *
 * <p>Ignore the result entirely when a strict policy already throws on rejection:
 *
 * <pre>{@code
 * holder.updateAndSave(config -> config.hudScale = 3);
 * }</pre>
 *
 * <p>{@link Rejected} appears when a fallback policy discards the candidate. Validation
 * rejections carry their violations; a failed fallback write may carry an empty list because the
 * candidate was never published.
 */
public sealed interface UpdateResult {

    /**
     * Returns the result used when the candidate was validated and published.
     *
     * @return the shared {@link Published} instance; never {@code null}
     */
    static UpdateResult published() {
        return Published.INSTANCE;
    }

    /**
     * Returns the result used when a fallback policy discarded the candidate.
     *
     * @param violations rules that rejected the candidate; must not be {@code null}, and may
     *                   be empty when the candidate was discarded for a non-validation reason
     * @return a {@link Rejected} carrying an immutable copy of {@code violations}
     * @throws NullPointerException if {@code violations} is {@code null}
     */
    static UpdateResult rejected(List<Violation> violations) {
        return new Rejected(violations);
    }

    /**
     * Reports whether the candidate reached the published state.
     *
     * @return {@code true} for {@link Published}, {@code false} for {@link Rejected}
     */
    default boolean accepted() {
        return this instanceof Published;
    }

    /**
     * Returns the rules that rejected the candidate.
     *
     * @return immutable violation list; never {@code null}, and empty for {@link Published}
     */
    List<Violation> violations();

    /**
     * Signals that the candidate passed validation and became the published state.
     *
     * <p>Carries no data, so a single instance is reused; obtain it through
     * {@link UpdateResult#published()}.
     */
    final class Published implements UpdateResult {

        private static final Published INSTANCE = new Published();

        private Published() {
        }

        @Override
        public List<Violation> violations() {
            return List.of();
        }

        @Override
        public String toString() {
            return "UpdateResult.Published";
        }
    }

    /**
     * Signals that the candidate was discarded and the previous state still stands.
     *
     * @param violations rules that rejected the candidate; immutable and possibly empty when a
     *                   non-validation operation, such as persistence, failed
     */
    record Rejected(List<Violation> violations) implements UpdateResult {
        public Rejected {
            violations = List.copyOf(Objects.requireNonNull(violations, "violations"));
        }
    }
}
