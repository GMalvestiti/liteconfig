package com.gmalvestiti.minecraft.liteconfig.api;

/**
 * A removable config-listener registration.
 */
@FunctionalInterface
public interface ConfigSubscription extends AutoCloseable {

    /** A shared subscription whose {@link #close()} method does nothing. */
    ConfigSubscription NONE = () -> {};

    /**
     * Removes the listener represented by this subscription.
     *
     * <p>Closing a subscription more than once has no additional effect.
     */
    @Override
    void close();
}
