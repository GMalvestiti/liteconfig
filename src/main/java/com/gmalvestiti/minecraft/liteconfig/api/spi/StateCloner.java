package com.gmalvestiti.minecraft.liteconfig.api.spi;

import com.gmalvestiti.minecraft.liteconfig.exception.LiteConfigException;

/**
 * Copies config state without sharing mutable nested objects.
 *
 * <p>LiteConfig copies for caller-owned snapshots, update and sync candidates, the separately
 * published state, and {@code beforeSave} isolation. The default is a JSON round-trip; declare a
 * hand-written cloner on {@code @Config} when copying is a measured hot path:
 *
 * <pre>{@code
 * public final class MyModConfigCloner implements StateCloner<MyModConfig> {
 *
 *     @Override
 *     public MyModConfig copy(MyModConfig source) {
 *         MyModConfig copy = new MyModConfig();
 *         copy.hudScale = source.hudScale;
 *         copy.hiddenHints = new ArrayList<>(source.hiddenHints); // copy, don't share
 *         return copy;
 *     }
 * }
 *
 * @Config(name = "mymod", stateCloner = MyModConfigCloner.class)
 * public final class MyModConfig {}
 * }</pre>
 *
 * <p>An implementation must preserve every persisted field: dropping one during copy corrupts
 * live state just as surely as dropping it during serialization. It must also be safe to call
 * from the config worker, while other threads may read the published state.
 *
 * <p>An implementation must declare a no-argument constructor that LiteConfig can access
 * reflectively. It is instantiated once for each config registration.
 *
 * @param <T> config root type copied by this cloner
 */
@FunctionalInterface
public interface StateCloner<T> {

    /**
     * Creates an isolated deep copy of {@code source}.
     *
     * @param source state to copy; LiteConfig passes a non-null root instance
     * @return a distinct instance with no shared mutable children; never the same reference as
     *         {@code source}
     * @throws LiteConfigException when the copy
     *     cannot be produced and the failure should be handled by the active policy
     */
    T copy(T source);
}
