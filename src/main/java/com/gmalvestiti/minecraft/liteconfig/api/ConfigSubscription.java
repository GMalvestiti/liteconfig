package com.gmalvestiti.minecraft.liteconfig.api;

/**
 * A removable config-listener registration.
 */
@FunctionalInterface
public interface ConfigSubscription extends AutoCloseable {

    ConfigSubscription NONE = () -> {};

    @Override
    void close();
}
