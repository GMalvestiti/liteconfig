package com.gmalvestiti.minecraft.liteconfig.reflection.listener;

import com.gmalvestiti.minecraft.liteconfig.reflection.ConfigFieldAccess;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Field;
import java.util.Objects;

record ConfigFieldListener(Field[] chain, MethodHandle handle) {

    void invokeIfChanged(
        ConfigFieldAccess fieldAccess,
        Object oldState,
        Object newState,
        boolean fromSync
    ) throws Throwable {
        Object oldOwner = ownerOf(fieldAccess, oldState);
        Object newOwner = ownerOf(fieldAccess, newState);

        Object oldValue = oldOwner == null ? null : fieldAccess.read(chain[chain.length - 1], oldOwner);
        Object newValue = newOwner == null ? null : fieldAccess.read(chain[chain.length - 1], newOwner);

        if (Objects.deepEquals(oldValue, newValue)) {
            return;
        }

        if (newOwner != null) {
            handle.invokeExact(newOwner, oldValue, newValue, fromSync);
        }
    }

    private Object ownerOf(ConfigFieldAccess fieldAccess, Object root) {
        Object owner = root;

        for (int index = 0; owner != null && index < chain.length - 1; index++) {
            owner = fieldAccess.read(chain[index], owner);
        }

        return owner;
    }
}
