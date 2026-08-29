package com.gmalvestiti.minecraft.liteconfig.api;

import com.gmalvestiti.minecraft.liteconfig.api.spi.Violation;

import java.util.List;

/**
 * Adds lifecycle hooks and validation to the config class that implements it.
 *
 * <p>Implement it on the config class itself — there is no builder option and no separate
 * extension instance. Every hook runs on the config object being processed, so {@code this} is
 * always the state to inspect or adjust:
 *
 * <pre>{@code
 * @Config(name = "mymod")
 * public final class MyModConfig implements ConfigExtension {
 *
 *     public int hudScale = 2;
 *
 *     @Override
 *     public void afterLoad() {
 *         hudScale = Math.max(hudScale, 1);
 *     }
 *
 *     @Override
 *     public void validate(List<Violation> violations) {
 *         if (hudScale > 8) {
 *             violations.add(Violation.of("hud-scale.max", "hudScale must be <= 8, was " + hudScale));
 *         }
 *     }
 * }
 * }</pre>
 *
 * <p>The holder runs hooks on its single config root. {@link #afterLoad()} runs after loading,
 * {@link #validate(List)} runs for loaded and candidate states, and {@link #beforeSave()} runs on
 * the private copy being written.
 */
public interface ConfigExtension {

    /**
     * Adjusts a freshly loaded state before publication.
     *
     * <p>Assign directly to fields on {@code this}; the hook is invoked on the loaded config
     * object.
     *
     * <p><strong>Not called on the update path.</strong> When a caller submits a value via
     * {@link ConfigHolder#update} or
     * {@link ConfigHolder#updateAndSave}, this hook
     * does not run. If your {@link #validate} implementation expects values to be pre-clamped
     * by this hook, the same raw values submitted via update will fail validation. Keep
     * validators self-contained, or clamp inside the update mutator.
     */
    default void afterLoad() {}

    /**
     * Adjusts the state copy immediately before it is serialized.
     *
     * <p>The receiver is the copy that will be written, not the published instance,
     * so changes made here are persisted but not published as a side effect.
     */
    default void beforeSave() {}

    /**
     * Checks the values on {@code this} and reports every rule that fails.
     *
     * <p>Called on every candidate — after load, after each update, and at construction. All
     * violations collected under the root are joined into one
     * {@code ConfigError.VALIDATION_FAILED} failure; the read or update policy then decides
     * whether it throws or degrades. A strict policy puts them on
     * {@code LiteConfigException.violations()}, a fallback update policy returns them in
     * {@code UpdateResult.Rejected}.
     *
     * <pre>{@code
     * @Override
     * public void validate(List<Violation> violations) {
     *     if (maxPlayers < 1) {
     *         violations.add(Violation.of("max-players.min", "maxPlayers must be >= 1, was " + maxPlayers));
     *     }
     * }
     * }</pre>
     *
     * <p>Implementations must be side-effect free: read {@code this}, never mutate it — use
     * {@link #afterLoad()} for corrections. A {@code null} entry, a blank {@link Violation#id()},
     * and throwing are all defects, which propagate regardless of policy.
     *
     * @param violations empty mutable sink for every failed rule; never {@code null}
     */
    default void validate(List<Violation> violations) {}
}
