package com.gmalvestiti.minecraft.liteconfig.metadata;

import com.gmalvestiti.minecraft.liteconfig.api.SyncMode;
import com.gmalvestiti.minecraft.liteconfig.api.metadata.ConfigProperty;
import com.gmalvestiti.minecraft.liteconfig.api.metadata.ConfigMetadata;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigError;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigScope;
import com.gmalvestiti.minecraft.liteconfig.reflection.ConfigFieldAccess;
import com.gmalvestiti.minecraft.liteconfig.reflection.ConfigObjectFactory;
import com.gmalvestiti.minecraft.liteconfig.shared.PropertyPath;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ConfigMetadataFactory {

    private ConfigMetadataFactory() {}

    public static ConfigMetadata create(
        Class<?> rootType,
        ConfigScope scope,
        ConfigFieldPlan fields
    ) {
        fields.requireAccessible(scope);
        DeclaredConfig declaredConfig = DeclaredConfig.of(rootType);
        Object defaults = ConfigObjectFactory.newInstance(rootType, scope);

        return new ConfigMetadata(
            rootType,
            declaredConfig.comment(),
            declaredConfig.version(),
            propertiesOf(rootType, defaults, "", SyncMode.INHERIT, new LinkedHashSet<>(), scope, fields)
        );
    }

    private static List<ConfigProperty> propertiesOf(
        Class<?> owner,
        Object defaultOwner,
        String prefix,
        SyncMode parentMode,
        Set<Class<?>> ancestors,
        ConfigScope scope,
        ConfigFieldPlan fieldPlan
    ) {

        if (!ancestors.add(owner)) {
            return List.of();
        }

        DeclaredConfig declaredConfig = DeclaredConfig.of(owner);
        rejectInvalidKeys(owner, declaredConfig, scope);

        SyncMode ownerMode = parentMode == SyncMode.INHERIT && declaredConfig.sync()
            ? SyncMode.ENABLED : parentMode;

        List<ConfigProperty> properties = new ArrayList<>();
        for (DeclaredConfig.Property property : fieldPlan.properties(owner)) {
            properties.add(propertyOf(
                property, defaultOwner, prefix, ownerMode, ancestors, scope, fieldPlan));
        }

        ancestors.remove(owner);
        return List.copyOf(properties);
    }

    private static ConfigProperty propertyOf(
        DeclaredConfig.Property described,
        Object defaultOwner,
        String prefix,
        SyncMode parentMode,
        Set<Class<?>> ancestors,
        ConfigScope scope,
        ConfigFieldPlan fieldPlan
    ) {

        rejectProblems(described, scope);

        String path = PropertyPath.child(prefix, described.key());
        SyncMode mode = parentMode == SyncMode.DISABLED || described.sync() == SyncMode.INHERIT
            ? parentMode : described.sync();

        Object defaultValue = defaultValueOf(described, defaultOwner, scope);
        if (defaultValue instanceof String text && !described.constraints().allowedValues().isEmpty()) {
            defaultValue = described.constraints().normalization().apply(text, described.constraints().trim());
        }

        boolean descendable = fieldPlan.descendable(described);
        List<ConfigProperty> children = descendable
            ? propertiesOf(
                described.type(), defaultValue, path, mode, ancestors, scope, fieldPlan)
            : List.of();

        return new ConfigProperty(
            path,
            described.key(),
            described.fieldName(),
            described.type(),
            descendable ? null : defaultValue,
            described.comment(),
            described.translationKey(),
            described.restart(),
            mode == SyncMode.ENABLED,
            described.constraints(),
            children
        );
    }

    private static Object defaultValueOf(
        DeclaredConfig.Property described,
        Object defaultOwner,
        ConfigScope scope
    ) {
        if (defaultOwner == null) {
            return null;
        }

        return new ConfigFieldAccess(scope).read(described.field(), defaultOwner);
    }

    private static void rejectProblems(DeclaredConfig.Property described, ConfigScope scope) {
        if (described.problems().isEmpty()) {
            return;
        }

        throw scope.exception(
            ConfigError.INVALID_CONSTRAINT,
            described.fieldName(),
            described.field().getDeclaringClass().getName(),
            String.join("; ", described.problems()));
    }

    private static void rejectInvalidKeys(
        Class<?> owner,
        DeclaredConfig descriptor,
        ConfigScope scope
    ) {
        if (descriptor.keyProblems().isEmpty()) {
            return;
        }

        switch (descriptor.keyProblems().getFirst()) {
            case DeclaredConfig.InvalidKey invalid -> throw scope.exception(
                ConfigError.INVALID_ENTRY_NAME,
                invalid.field().getName(),
                invalid.field().getDeclaringClass().getName(),
                invalid.key());
            case DeclaredConfig.DuplicateKey duplicate -> throw scope.exception(
                ConfigError.DUPLICATE_ENTRY_NAME,
                duplicate.first().getName(),
                duplicate.second().getName(),
                owner.getName(),
                duplicate.key());
        }
    }
}
