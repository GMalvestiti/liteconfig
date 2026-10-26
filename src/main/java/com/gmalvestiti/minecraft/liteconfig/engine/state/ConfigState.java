package com.gmalvestiti.minecraft.liteconfig.engine.state;

import com.gmalvestiti.minecraft.liteconfig.api.spi.StateCloner;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigError;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigScope;

import java.util.Objects;
import java.util.Optional;
import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * Stores and isolates the canonical and published state of one registered config.
 *
 * <p>The canonical state is the authoritative value used by load, save, and update operations.
 * The published value is what {@code ConfigHolder#data()} returns; callers must treat it as
 * read-only.
 *
 * <p><strong>The active canonical and published slots are equal but never aliased.</strong> Because
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
 * <p>Every holder of a config shares this state. Nested mutation is rejected while mutation work
 * is running. The volatile snapshot safely publishes read-only values to readers on other threads.
 *
 * @param <T> config root type stored by this state
 */
public final class ConfigState<T> {

    private final StateCloner<T> cloner;
    private final ConfigScope scope;
    private volatile Snapshot<T> snapshot;
    private final Object operationLock = new Object();
    private boolean operationInProgress;

    public ConfigState(StateCloner<T> cloner, ConfigScope scope, T initial) {
        this.cloner = Objects.requireNonNull(cloner, "cloner");
        this.scope = Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(initial, "initial");
        this.snapshot = new Snapshot<>(initial, copy(initial), initial, null, null, false);
    }

    public T published() {
        return snapshot.published();
    }

    public T canonical() {
        return snapshot.canonical();
    }

    public T copyOfCanonical() {
        return copy(snapshot.canonical());
    }

    public boolean remoteActive() {
        return snapshot.remote();
    }

    public Optional<T> pending() {
        T pending = snapshot.pending();
        return pending == null ? Optional.empty() : Optional.of(copy(pending));
    }

    public boolean hasPending() {
        return snapshot.pending() != null;
    }

    public boolean pendingAt(Path path) {
        Snapshot<T> current = snapshot;
        return current.pending() != null
            && (current.pendingPath() == null || current.pendingPath().equals(path));
    }

    public T copyOfLocal() {
        return copy(snapshot.local());
    }

    public T copyOf(T source) {
        return copy(Objects.requireNonNull(source, "source"));
    }

    public Object operationLock() {
        return operationLock;
    }

    /**
     * Runs one read-modify-write sequence with no other one in progress.
     *
     * @param work reads the current state and replaces it; may throw to leave the state untouched
     * @return whatever {@code work} returned
     */
    public <R> R writing(Supplier<R> work) {
        synchronized (operationLock) {
            if (operationInProgress) {
                throw scope.exception(ConfigError.NESTED_CONFIG_OPERATION);
            }

            operationInProgress = true;
            try {
                return work.get();
            } finally {
                operationInProgress = false;
            }
        }
    }

    public Transition<T> replace(T nextState) {
        Objects.requireNonNull(nextState, "nextState");

        Snapshot<T> current = snapshot;
        Snapshot<T> next = new Snapshot<>(nextState, copy(nextState), nextState,
            current.pending(), current.pendingPath(), false);
        snapshot = next;

        return new Transition<>(current.canonical(), next.canonical(), next.published());
    }

    public Transition<T> replaceRemote(T active, T pending, Path pendingPath) {
        Objects.requireNonNull(active, "active");

        Snapshot<T> current = snapshot;
        Snapshot<T> next = new Snapshot<>(active, copy(active), current.local(),
            pending, pending == null ? null : pendingPath, true);
        snapshot = next;

        return new Transition<>(current.canonical(), next.canonical(), next.published());
    }

    public Transition<T> replaceLocal(T active, T pending, Path pendingPath) {
        Objects.requireNonNull(active, "active");

        Snapshot<T> current = snapshot;
        Snapshot<T> next = new Snapshot<>(active, copy(active), active,
            pending, pending == null ? null : pendingPath, false);
        snapshot = next;

        return new Transition<>(current.canonical(), next.canonical(), next.published());
    }

    public Transition<T> clearRemote() {
        Snapshot<T> current = snapshot;
        return replace(current.local());
    }

    public Saved<T> checkpoint() {
        return new Saved<>(snapshot);
    }

    public void restore(Saved<T> saved) {
        snapshot = saved.snapshot;
    }

    public record Transition<T>(T before, T after, T published) {}

    private record Snapshot<T>(T canonical, T published, T local, T pending, Path pendingPath, boolean remote) {}

    public record Saved<T>(Snapshot<T> snapshot) {
        public Saved {
            Objects.requireNonNull(snapshot, "snapshot");
        }
    }

    private T copy(T source) {
        return StateCopies.isolated(cloner, source, scope);
    }
}
