package com.gmalvestiti.minecraft.liteconfig.api;

import com.gmalvestiti.minecraft.liteconfig.api.metadata.ConfigMetadata;
import com.gmalvestiti.minecraft.liteconfig.exception.LiteConfigException;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Operates on one config root after {@link ConfigBuilder#create()} completes.
 *
 * <p>Read the published state through {@link #data()} and treat it as read-only. Take a
 * {@link #copy()} when you need an object you may mutate, or a reading that cannot shift
 * underneath you. Change values through {@link #update(Consumer)} or
 * {@link #updateAndSave(Consumer)}, which mutate a private candidate, validate it, and publish
 * only accepted results:
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
 * <p>All lifecycle operations are serialized on this config's worker lane. Synchronous methods
 * wait for their queued work; asynchronous methods return its {@link CompletableFuture}. Hooks run
 * on that lane, while lifecycle listeners are dispatched to their configured game-thread executor.
 * Starting another config operation from a mutator or hook is rejected instead of risking a
 * cross-queue deadlock or overwriting nested changes.
 *
 * <p>A logical-side listener is skipped and logged when that side has no active main-thread
 * executor. Work queued for a server that stops or is replaced is discarded rather than delivered
 * into the next server lifecycle.
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
     * <p>This read remains available after {@link #close()}.
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
     * changes. This read remains available after {@link #close()}.
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
     * Re-reads persisted state and publishes it when accepted.
     *
     * <p>{@link ConfigBuilder#create()} already performed the initial load, so this is for
     * picking up a file edited after startup. A failed read is handled by the read policy:
     * {@code FALLBACK} moves a malformed or invalid file aside and restores defaults in memory,
     * leaving the file to be rewritten by the next save; {@code STRICT} throws.
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
     * holder.updateAndSave(config -> config.hudScale = 3);   // memory and config/mymod.json5
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
     * Loads persisted state on the config worker.
     *
     * @return a future that completes after publication
     */
    CompletableFuture<Void> loadAsync();

    /**
     * Mutates and publishes a private candidate on the config worker.
     *
     * @param mutator edits the candidate; must not be {@code null}
     * @return a future completing with the update result
     */
    CompletableFuture<UpdateResult> updateAsync(Consumer<T> mutator);

    /**
     * Mutates, publishes, and saves a private candidate on the config worker.
     *
     * @param mutator edits the candidate; must not be {@code null}
     * @return a future completing with the update result
     */
    CompletableFuture<UpdateResult> updateAndSaveAsync(Consumer<T> mutator);

    /**
     * Saves the current state on the config worker.
     *
     * @return a future completing after persistence
     */
    CompletableFuture<Void> saveAsync();

    /**
     * Registers an update listener on the selected logical side's main thread.
     * {@link ConfigSide#BOTH} registers one listener for each side.
     *
     * @param side     logical side whose main thread receives the callback
     * @param listener the callback, or {@code null} to register nothing
     * @return an idempotent subscription that removes the listener when closed
     */
    ConfigSubscription onUpdate(ConfigSide side, Consumer<T> listener);

    /**
     * Registers a load listener on the selected logical side's main thread.
     * {@link ConfigSide#BOTH} registers one listener for each side.
     *
     * @param side     logical side whose main thread receives the callback
     * @param listener the callback, or {@code null} to register nothing
     * @return an idempotent subscription that removes the listener when closed
     */
    ConfigSubscription onLoad(ConfigSide side, Consumer<T> listener);

    /**
     * Registers a save listener on the selected logical side's main thread.
     * {@link ConfigSide#BOTH} registers one listener for each side.
     *
     * @param side     logical side whose main thread receives the callback
     * @param listener the callback, or {@code null} to register nothing
     * @return an idempotent subscription that removes the listener when closed
     */
    ConfigSubscription onSave(ConfigSide side, Consumer<T> listener);

    /**
     * Releases this holder and removes every lifecycle listener registered through it.
     *
     * <p>The shared registration is released after the last holder closes. {@link #data()} and
     * {@link #metadata()} remain readable; lifecycle and mutation operations are rejected.
     */
    @Override
    void close();
}
