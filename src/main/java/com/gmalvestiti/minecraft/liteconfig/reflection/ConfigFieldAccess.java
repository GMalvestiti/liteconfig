package com.gmalvestiti.minecraft.liteconfig.reflection;

import com.gmalvestiti.minecraft.liteconfig.exception.ConfigError;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigScope;
import com.gmalvestiti.minecraft.liteconfig.exception.LiteConfigException;

import java.lang.reflect.Field;

public final class ConfigFieldAccess {

    private final ConfigScope scope;

    public ConfigFieldAccess(ConfigScope scope) {
        this.scope = scope;
    }

    public Object read(Field field, Object target) {
        try {
            return field.get(target);
        } catch (IllegalAccessException | RuntimeException ex) {
            throw accessFailure(field, ex);
        }
    }

    public void write(Field field, Object target, Object value) {
        try {
            field.set(target, value);
        } catch (IllegalAccessException | RuntimeException ex) {
            throw accessFailure(field, ex);
        }
    }

    private LiteConfigException accessFailure(Field field, Exception cause) {
        return scope.exception(
            ConfigError.REFLECTION_ACCESS, cause, field.getName(), field.getDeclaringClass().getName());
    }
}
