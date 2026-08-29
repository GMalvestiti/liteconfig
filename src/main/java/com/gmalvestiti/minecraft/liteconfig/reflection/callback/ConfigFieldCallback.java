package com.gmalvestiti.minecraft.liteconfig.reflection.callback;

import com.gmalvestiti.minecraft.liteconfig.reflection.ConfigFieldAccess;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;

record ConfigFieldCallback(Field[] chain, Method method) {

    void invokeIfChanged(
        ConfigFieldAccess fieldAccess,
        Object oldState,
        Object newState,
        boolean fromSync
    ) throws IllegalAccessException, InvocationTargetException {
        Object oldOwner = ownerOf(fieldAccess, oldState);
        Object newOwner = ownerOf(fieldAccess, newState);

        Object oldValue = oldOwner == null ? null : fieldAccess.read(chain[chain.length - 1], oldOwner);
        Object newValue = newOwner == null ? null : fieldAccess.read(chain[chain.length - 1], newOwner);

        if (Objects.deepEquals(oldValue, newValue)) {
            return;
        }

        if (newOwner != null) {
            method.invoke(newOwner, oldValue, newValue, fromSync);
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
