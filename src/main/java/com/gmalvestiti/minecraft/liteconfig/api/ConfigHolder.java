package com.gmalvestiti.minecraft.liteconfig.api;

import com.gmalvestiti.minecraft.liteconfig.api.metadata.ConfigMetadata;
import com.gmalvestiti.minecraft.liteconfig.exception.LiteConfigException;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Operates on one config root after {@link ConfigBuilder#create()} completes.
 *
 * <p>Read the published state safely from any thread through {@link #data()} and treat it as
 * read-only. Use {@link #copy()} for a private, editable snapshot. Change values through
 * {@link #update(Consumer)} or {@link #updateAndSave(Consumer)},
 * which mutate a private candidate, validate it, and publish the accepted result:
 *
 * <pre>{@code
 * MyModConfig shared = holder.data();   // cheap, shared, read-only
 * MyModConfig mine = holder.copy();     // deep copy the caller owns
 *
 * holder.update(config -> config.showHints = false);   // publish in memory
 * holder.updateAndSave(config -> config.hudScale = 3); // publish and write
 * }</pre>
 *
 * <p>Both update methods return an {@link UpdateResult}. A strict update policy throws on
 * rejection, so the result is always {@link UpdateResult.Published}; a fallback policy returns
 * quietly, making the result the only way to learn which rules failed:
 *
 * <pre>{@code
 * UpdateResult result = holder.updateAndSave(config -> config.hudScale = 99);
 * if (!result.accepted()) {
 *     result.violations().forEach(v -> LOGGER.warn("{}: {}", v.id(), v.message()));
 * }
 * }</pre>
 *
 * <p>Loads, updates, and saves complete synchronously on the caller, with failures handled by
 * the configured policies.
 *
 * <p>Starting another operation on the same config from a mutator or lifecycle hook is rejected,
 * including through another reference to this holder.
 *
 * <p>Each live registration has one holder shared by repeated {@link ConfigBuilder#create()}
 * calls; builder events are appended on each call. Separate configs have separate holders.
 *
 * <p>Not intended for implementation outside LiteConfig: methods may be added in any minor
 * release. Build holders through {@code LiteConfig.holder(...)}, and wrap this type behind an
 * interface you own if you need a seam for testing.
 *
 * @param <T> the root config type
 */
public interface ConfigHolder<T> extends AutoCloseable {

    /**
     * Returns the currently published state.
     *
     * <p>This read is lock-free and safe from any thread. Treat the returned object and its nested
     * values as read-only.
     *
     * <p>After this holder is closed, this continues to return the last published state. This
     * hot-path read deliberately does not check whether the holder is closed.
     *
     * @return the shared state instance; never {@code null}; treat it as read-only
     */
    T data();

    /**
     * Returns the structural description of this config root.
     *
     * <p>Everything the annotations declare, as plain data: the properties of the root, their
     * defaults, their documentation, their translation keys, and the rules they enforce. It is
     * what a config screen reads to build its controls, and what a validator reads to explain a
     * rejected value:
     *
     * <pre>{@code
     * holder.metadata().flatten()
     *     .filter(property -> property.restart())
     *     .forEach(property -> LOGGER.info("{} needs a restart", property.path()));
     * }</pre>
     *
     * <p>Computed once when the holder is built, so calling this is cheap and the result never
     * changes. This read is rejected after {@link #close()}.
     *
     * @return the immutable metadata of {@code T}; never {@code null}
     */
    ConfigMetadata metadata();

    /**
     * Returns a private deep copy of the current state.
     *
     * <p>Unlike {@link #data()}, the returned object belongs to the caller: mutating it affects
     * nothing the holder tracks, and it cannot change underneath you while another thread loads
     * or updates.
     *
     * <pre>{@code
     * MyModConfig staged = holder.copy();
     * staged.hudScale = 4;                                  // nothing published yet
     * holder.updateAndSave(config -> config.hudScale = staged.hudScale);
     * }</pre>
     *
     * <p>Each call runs the configured cloner, so read through {@link #data()} on hot paths and
     * copy only when isolation is actually needed.
     *
     * @return a fresh deep copy of the current state; never {@code null}
     */
    T copy();

    /**
     * Returns an isolated copy of the full desired state while restart settings are pending.
     * Local CLIENT and SERVER edits wait for game startup; WORLD edits wait for the next world load.
     * Use {@link #save()} or {@link #updateAndSave(Consumer)} to persist local selections;
     * {@link #update(Consumer)} alone changes only memory.
     * Synced SERVER restart selections are saved automatically and survive disconnect.
     * WORLD remote selections belong only to that world session and are cleared on disconnect;
     * its initial sync on the next join applies the current world settings.
     *
     * @return the desired snapshot, or empty when no restart fields differ from active values
     */
    Optional<T> pending();

    /**
     * Returns this config's declared ownership type.
     */
    ConfigType type();

    /**
     * Returns whether local ownership or an applied remote snapshot currently activates this config.
     */
    boolean isActive();

    /**
     * Returns the active local config file, or empty when this config has no active local path.
     */
    Optional<Path> path();

    /**
     * Re-reads persisted state and publishes it when accepted.
     * Refused while locally inactive, remotely controlled, or read-only.
     *
     * <p>{@link ConfigBuilder#create()} already performed the initial load, so this is for
     * picking up a file edited after startup. A failed read is handled by the read policy:
     * {@code FALLBACK} moves a malformed or invalid file aside and falls back to defaults subject
     * to the same restart-only rules, leaving the file to be rewritten by the next save;
     * {@code STRICT} throws.
     *
     * <p>Restart-only changes remain pending for every config type.
     *
     * @throws LiteConfigException when the read policy is strict and loading, parsing, or load-time validation fails;
     *     also when a defect error occurs
     */
    void load();

    /**
     * Mutates a private candidate and publishes it if validation accepts the result.
     *
     * <p>Never writes to disk — use {@link #updateAndSave(Consumer)} for that. Under a fallback
     * update policy a rejected candidate is logged and discarded, the previous state stays
     * published, and the failed rules come back on the result.
     *
     * <p>Restart-only edits for every config type are accepted into {@link #pending()} while other
     * changes become active. Later updates start from the desired snapshot so they retain pending
     * selections. Pending SERVER settings take effect after a game restart.
     *
     * <pre>{@code
     * holder.update(config -> config.showHints = false);
     * }</pre>
     *
     * @param mutator edits a private candidate copy; must not be {@code null}
     * @return {@link UpdateResult.Published} when the candidate became the new state,
     *         {@link UpdateResult.Rejected} only when a fallback update policy discarded it;
     *         never {@code null}
     * @throws LiteConfigException when the update policy is strict and validation rejects the candidate;
     *     also when the mutator is {@code null} or another defect error occurs
     */
    UpdateResult update(Consumer<T> mutator);

    /**
     * Mutates, validates, publishes, and saves one accepted candidate.
     *
     * <p>A candidate rejected under a fallback update or write policy is neither published nor
     * saved. Validation failures carry their failed rules; persistence failures may return an
     * empty violation list.
     *
     * <pre>{@code
     * holder.updateAndSave(config -> config.hudScale = 3); // memory and config/mymod/mymod.json5
     * }</pre>
     *
     * @param mutator edits a private candidate copy; must not be {@code null}
     * @return {@link UpdateResult.Published} when the candidate was published and saved,
     *         {@link UpdateResult.Rejected} only when a fallback update policy discarded it;
     *         never {@code null}
     * @throws LiteConfigException when the update or write policy is strict and the operation fails;
     *     also when the mutator is {@code null} or another defect error occurs
     */
    UpdateResult updateAndSave(Consumer<T> mutator);

    /**
     * Saves the current state to storage.
     *
     * @throws LiteConfigException when the write policy is strict and persistence fails; also when a defect error occurs
     */
    void save();

    /**
     * Registers a synchronous update event on the initiating caller.
     *
     * <p>The event runs after every accepted local update, including one that leaves values
     * unchanged. Server sync notifies it only when the effective published values change.
     * Rejected operations do not notify it.
     *
     * @param listener the event listener, or {@code null} to register nothing
     * @return an idempotent subscription that removes only this listener when closed;
     *         a no-op subscription if the registration is closed
     */
    ConfigSubscription onUpdate(Consumer<T> listener);

    /**
     * Registers a synchronous load event on the initiating caller.
     *
     * <p>The event runs after a successful explicit {@link #load()} or local WORLD activation,
     * but not after the build-time load or a load that falls back to defaults.
     *
     * @param listener the event listener, or {@code null} to register nothing
     * @return an idempotent subscription that removes only this listener when closed;
     *         a no-op subscription if the registration is closed
     */
    ConfigSubscription onLoad(Consumer<T> listener);

    /**
     * Registers a synchronous save event on the initiating caller.
     *
     * <p>The event runs after {@link #save()} or {@link #updateAndSave(Consumer)} reaches
     * storage. A write failure handled by a fallback policy does not notify it.
     *
     * @param listener the event listener, or {@code null} to register nothing
     * @return an idempotent subscription that removes only this listener when closed;
     *         a no-op subscription if the registration is closed
     */
    ConfigSubscription onSave(Consumer<T> listener);

    /**
     * Closes the registration's canonical holder for every alias and removes every event.
     *
     * <p>Closing through any reference is idempotent across all aliases. The registration and file
     * ownership are released once; persisted files are not deleted. Metadata, copying, and lifecycle operations are
     * rejected after closing, and later event-registration attempts return a no-op subscription.
     * {@link #data()} has no closed guard and must not be used after closing.
     *
     * <p>When closing during a state write, closed state and event removal take effect immediately,
     * but accepted work finishes before resources are released. If both fail, the operation's failure is thrown with the cleanup failure
     * suppressed. References previously returned to callers remain the callers' responsibility.
     */
    @Override
    void close();
}
