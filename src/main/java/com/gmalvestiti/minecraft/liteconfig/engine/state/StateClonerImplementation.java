package com.gmalvestiti.minecraft.liteconfig.engine.state;

import com.gmalvestiti.minecraft.liteconfig.api.spi.StateCloner;
import com.gmalvestiti.minecraft.liteconfig.storage.ConfigBinder;

public final class StateClonerImplementation implements StateCloner<Object> {

    @Override
    public Object copy(Object source) {
        return source == null ? null : copyValue(source);
    }

    @SuppressWarnings("unchecked")
    private static <T> T copyValue(T source) {
        return ConfigBinder.copy(source, (Class<T>) source.getClass());
    }
}
