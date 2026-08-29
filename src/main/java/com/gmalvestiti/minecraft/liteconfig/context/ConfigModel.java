package com.gmalvestiti.minecraft.liteconfig.context;

import com.gmalvestiti.minecraft.liteconfig.api.metadata.ConfigMetadata;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigScope;
import com.gmalvestiti.minecraft.liteconfig.reflection.ConfigExtensionLookup;
import com.gmalvestiti.minecraft.liteconfig.reflection.ConfigFieldAccess;
import com.gmalvestiti.minecraft.liteconfig.reflection.callback.ConfigFieldCallbacks;
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
    ConfigFieldCallbacks<T> callbacks
) {

    private static final ClassValue<Structure> STRUCTURES = new ClassValue<>() {
        @Override
        protected Structure computeValue(Class<?> type) {
            return new Structure(
                ConfigFieldPlan.of(type),
                ConfigExtensionLookup.resolve(type)
            );
        }
    };

    public ConfigModel {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(fieldAccess, "fieldAccess");
        Objects.requireNonNull(fields, "fields");
        Objects.requireNonNull(extensions, "extensions");
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(callbacks, "callbacks");
    }

    public static <T> ConfigModel<T> of(Class<T> type, ConfigScope scope) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(scope, "scope");

        Structure structure = STRUCTURES.get(type);
        ConfigFieldAccess fieldAccess = new ConfigFieldAccess(scope);
        ConfigFieldPlan fields = structure.fields();
        ConfigMetadata metadata = ConfigMetadataFactory.create(type, scope, fields);
        return new ConfigModel<>(
            type,
            scope,
            fieldAccess,
            fields,
            structure.extensions(),
            metadata,
            ConfigFieldCallbacks.resolve(type, fields, scope, fieldAccess)
        );
    }

    public String typeName() {
        return type.getName();
    }

    public String syncId() {
        return scope.modId() + ":" + typeName();
    }

    private record Structure(
        ConfigFieldPlan fields,
        ConfigExtensionLookup extensions
    ) {}
}
