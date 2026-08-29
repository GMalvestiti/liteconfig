package com.gmalvestiti.minecraft.liteconfig.engine.state;

import com.gmalvestiti.minecraft.liteconfig.api.spi.StateCloner;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigError;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigScope;

public final class StateCopies {

    private StateCopies() {}

    public static <T> T isolated(StateCloner<T> cloner, T source, ConfigScope scope) {
        T copy = cloner.copy(source);

        if (copy == null || copy == source) {
            throw scope.exception(
                ConfigError.INVALID_STATE_COPY,
                source.getClass().getName(),
                copy == null ? "returned null" : "returned the source instance");
        }

        return copy;
    }
}
