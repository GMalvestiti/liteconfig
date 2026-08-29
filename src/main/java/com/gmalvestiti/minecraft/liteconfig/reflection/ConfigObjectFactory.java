package com.gmalvestiti.minecraft.liteconfig.reflection;

import com.gmalvestiti.minecraft.liteconfig.exception.ConfigError;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigScope;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

public final class ConfigObjectFactory {

    private static final ClassValue<Constructor<?>> CONSTRUCTORS = new ClassValue<>() {
        @Override
        protected Constructor<?> computeValue(Class<?> type) {
        try {
            Constructor<?> constructor = type.getDeclaredConstructor();
            constructor.trySetAccessible();
            return constructor;
        } catch (NoSuchMethodException ex) {
            return null;
        }
        }
    };

    private ConfigObjectFactory() {}

    public static <T> T newInstance(Class<T> type, ConfigScope scope) {
        Constructor<?> constructor = CONSTRUCTORS.get(type);

        if (constructor == null) {
            throw scope.exception(ConfigError.MISSING_DEFAULT_CONSTRUCTOR, type.getName());
        }

        try {
            return type.cast(constructor.newInstance());
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause();
            throw scope.exception(ConfigError.INITIALIZATION_FAILED, cause, cause.getMessage());
        } catch (InstantiationException | IllegalAccessException ex) {
            throw scope.exception(ConfigError.INITIALIZATION_FAILED, ex, ex.getMessage());
        }
    }

    public static <T> T newInstanceOrNull(Class<T> type) {
        Constructor<?> constructor = CONSTRUCTORS.get(type);

        if (constructor == null) {
            return null;
        }

        try {
            return type.cast(constructor.newInstance());
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            return null;
        }
    }
}
