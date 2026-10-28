package com.gmalvestiti.minecraft.liteconfig.validation;

import com.gmalvestiti.minecraft.liteconfig.api.annotations.Config;
import com.gmalvestiti.minecraft.liteconfig.context.ConfigModel;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigError;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigScope;
import com.gmalvestiti.minecraft.liteconfig.migration.ConfigMigrations;
import com.gmalvestiti.minecraft.liteconfig.metadata.ConfigFieldPlan;
import com.gmalvestiti.minecraft.liteconfig.metadata.DeclaredConfig;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class ConfigModelValidator {

    private ConfigModelValidator() {}

    public static void validate(ConfigModel<?> model) {

        Class<?> rootType = model.type();
        ConfigScope scope = model.scope();

        if (rootType.isAnnotationPresent(Config.class)) {
            validateConfigClass(model, scope);
            return;
        }

        throw scope.exception(ConfigError.MISSING_CONFIG_MARKER, rootType.getName());
    }

    private static void validateConfigClass(ConfigModel<?> model, ConfigScope scope) {
        Class<?> configType = model.type();
        rejectConfigReferences(configType, model.fields(), scope, new HashSet<>());
        rejectReservedVersionKey(configType, scope);
        rejectInvalidMigrations(configType, scope);
    }

    private static void rejectConfigReferences(
        Class<?> type,
        ConfigFieldPlan fields,
        ConfigScope scope,
        Set<Class<?>> visited
    ) {
        if (!visited.add(type)) {
            return;
        }

        for (Field field : type.getDeclaredFields()) {
            if (field.getType().isAnnotationPresent(Config.class)) {
                throw scope.exception(ConfigError.CONFIG_REFERENCE_FORBIDDEN, field.getName(), type.getName());
            }
        }

        for (DeclaredConfig.Property property : fields.properties(type)) {
            if (fields.descendable(property)) {
                rejectConfigReferences(property.type(), fields, scope, visited);
            }
        }
    }

    private static void rejectReservedVersionKey(Class<?> configType, ConfigScope scope) {
        DeclaredConfig declaredConfig = DeclaredConfig.of(configType);
        if (declaredConfig.version() <= 0) {
            return;
        }

        DeclaredConfig.Property claimed = declaredConfig.property(ConfigMigrations.VERSION_KEY);
        if (claimed != null) {
            throw scope.exception(
                ConfigError.RESERVED_VERSION_KEY,
                claimed.field().getName(),
                configType.getName(),
                ConfigMigrations.VERSION_KEY);
        }
    }

    private static void rejectInvalidMigrations(Class<?> configType, ConfigScope scope) {
        List<String> problems = ConfigMigrations.of(configType).problems();

        if (!problems.isEmpty()) {
            throw scope.exception(ConfigError.INVALID_MIGRATION, configType.getName(), String.join("; ", problems));
        }
    }
}
