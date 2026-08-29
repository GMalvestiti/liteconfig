package com.gmalvestiti.minecraft.liteconfig.reflection;

import com.gmalvestiti.minecraft.liteconfig.api.ConfigExtension;

import java.lang.reflect.Method;

public record ConfigExtensionLookup(String label, boolean hasBeforeSave) {

    public static ConfigExtensionLookup resolve(Class<?> rootType) {
        return new ConfigExtensionLookup(
            rootType.getSimpleName(),
            ConfigExtension.class.isAssignableFrom(rootType) && overridesBeforeSave(rootType));
    }

    public ConfigExtension resolve(Object root) {
        return root instanceof ConfigExtension found ? found : null;
    }

    private static boolean overridesBeforeSave(Class<?> type) {
        try {
            Method method = type.getMethod("beforeSave");
            return method.getDeclaringClass() != ConfigExtension.class;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }
}
