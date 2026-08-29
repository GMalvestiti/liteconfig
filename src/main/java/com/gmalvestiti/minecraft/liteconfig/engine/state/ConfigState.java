package com.gmalvestiti.minecraft.liteconfig.engine.state;

import com.gmalvestiti.minecraft.liteconfig.api.spi.StateCloner;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigScope;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Stores and isolates the canonical and published state of one registered config.
 *
 * <p>The canonical state is the authoritative value used by load, save, and update operations.
 * The published value is what {@code ConfigHolder#data()} returns; callers must treat it as
 * read-only.
 *
 * <p><strong>The two slots are always equal but never aliased.</strong> Because
 * {@link #published()} returns a reference rather than a copy, and config objects are mutable, a
 * caller can write through the reference it receives. Keeping a separate canonical slot ensures
 * such writes can never reach disk or seed the next update.
 *
 * <p>{@link #published()} is the read hot path and must not allocate or lock. Mutation paths go
 * through {@link #writing(Supplier)}, which holds a lock for the whole copy-validate-replace
 * sequence so two of them cannot interleave and lose each other's changes:
 *
 * <pre>{@code
 * state.writing(() -> {
 *     T candidate = state.copyOfCanonical();
 *     consumer.accept(candidate);   // caller mutates the private copy
 *     validate(candidate);
 *     state.replace(candidate);
 *     return candidate;
 * });
 * }</pre>
 *
 * <p>A registration is shared by every holder of that config, and a synced config is written from
 * the client main thread that delivers a payload while an ASYNC holder writes from the config
 * worker, so more than one thread genuinely does reach these methods.
 *
 * @param <T> config root type stored by this state
 */
public final class ConfigState<T> {

    private final StateCloner<T> cloner;
    private final ConfigScope scope;
    private volatile Snapshot<T> snapshot;
    private final Object writeLock = new Object();

    public ConfigState(StateCloner<T> cloner, ConfigScope scope, T initial) {
        this.cloner = Objects.requireNonNull(cloner, "cloner");
        this.scope = Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(initial, "initial");
        this.snapshot = new Snapshot<>(initial, copy(initial));
    }

    public T published() {
        return snapshot.published();
    }

    public T canonical() {
        return snapshot.canonical();
    }

    /**
     * Returns a deep copy of the canonical state that the caller may mutate freely.
     *
     * <p>Intentionally expensive: runs the configured cloner every time.
     */
    public T copyOfCanonical() {
        return copy(snapshot.canonical());
    }

    public View<T> current() {
        Snapshot<T> current = snapshot;
        return new View<>(current.canonical(), current.published());
    }

    /**
     * Runs one read-modify-write sequence with no other one in progress.
     *
     * @param work reads the current state and replaces it; may throw to leave the state untouched
     * @return whatever {@code work} returned
     */
    public <R> R writing(Supplier<R> work) {
        synchronized (writeLock) {
            return work.get();
        }
    }

    public Transition<T> replace(T nextState) {
        Objects.requireNonNull(nextState, "nextState");

        Snapshot<T> current = snapshot;
        Snapshot<T> next = new Snapshot<>(nextState, copy(nextState));
        snapshot = next;

        return new Transition<>(current.canonical(), next.canonical(), next.published());
    }

    /**
     * The complete state change captured while the caller holds {@link #writing(Supplier)}.
     */
    public record Transition<T>(T before, T after, T published) {}

    /**
     * One exact canonical/published pair from the volatile snapshot.
     */
    public record View<T>(T canonical, T published) {}

    /**
     * Every slot together, so a reader can never catch one of them updated and the others not.
     */
    private record Snapshot<T>(T canonical, T published) {}

    private T copy(T source) {
        return StateCopies.isolated(cloner, source, scope);
    }
}
