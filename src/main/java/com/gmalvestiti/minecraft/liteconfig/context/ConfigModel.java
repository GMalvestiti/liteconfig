package com.gmalvestiti.minecraft.liteconfig.context;

import com.gmalvestiti.minecraft.liteconfig.api.SyncMode;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Config;
import com.gmalvestiti.minecraft.liteconfig.api.metadata.ConfigMetadata;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigError;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigScope;
import com.gmalvestiti.minecraft.liteconfig.reflection.ConfigExtensionLookup;
import com.gmalvestiti.minecraft.liteconfig.reflection.ConfigFieldAccess;
import com.gmalvestiti.minecraft.liteconfig.reflection.listener.ConfigFieldListeners;
import com.gmalvestiti.minecraft.liteconfig.metadata.ConfigFieldPlan;
import com.gmalvestiti.minecraft.liteconfig.metadata.ConfigMetadataFactory;

import java.util.Objects;

/**
 * Everything about a config root that is decided by its class alone.
 *
 * <p>Read once and shared by the parts that would otherwise each rediscover it: the metadata, the
 * extensions to call, and the reflection used to reach fields.
 *
 * @param <T> the root config type
 */
public record ConfigModel<T>(
    Class<T> type,
    ConfigScope scope,
    ConfigFieldAccess fieldAccess,
    ConfigFieldPlan fields,
    ConfigExtensionLookup extensions,
    ConfigMetadata metadata,
    ConfigFieldListeners<T> listeners
) {

    private static final ClassValue<ConfigExtensionLookup> EXTENSIONS = new ClassValue<>() {
        @Override
        protected ConfigExtensionLookup computeValue(Class<?> type) {
            return ConfigExtensionLookup.resolve(type);
        }
    };

    public ConfigModel {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(fieldAccess, "fieldAccess");
        Objects.requireNonNull(fields, "fields");
        Objects.requireNonNull(extensions, "extensions");
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(listeners, "listeners");
    }

    public static <T> ConfigModel<T> of(Class<T> type, ConfigScope scope) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(scope, "scope");

        Config config = type.getAnnotation(Config.class);
        if (config != null && config.sync() == SyncMode.INHERIT) {
            throw scope.exception(ConfigError.INVALID_CONFIG_SYNC, type.getName());
        }

        ConfigFieldPlan fields = ConfigFieldPlan.of(type);
        ConfigExtensionLookup extensions = EXTENSIONS.get(type);
        ConfigFieldAccess fieldAccess = new ConfigFieldAccess(scope);
        ConfigMetadata metadata = ConfigMetadataFactory.create(type, scope, fields);
        return new ConfigModel<>(
            type,
            scope,
            fieldAccess,
            fields,
            extensions,
            metadata,
            ConfigFieldListeners.resolve(type, fields, scope, fieldAccess)
        );
    }

    public String typeName() {
        return type.getName();
    }

    public String syncId() {
        return scope.modId() + ":" + typeName();
    }

}
