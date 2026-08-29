package com.gmalvestiti.minecraft.liteconfig.metadata;

import com.gmalvestiti.minecraft.liteconfig.api.LiteConfig;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigError;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigScope;
import com.gmalvestiti.minecraft.liteconfig.reflection.PersistedFields;

import java.lang.reflect.Field;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class ConfigFieldPlan {

    private static final ClassValue<ConfigFieldPlan> PLANS = new ClassValue<>() {
        @Override
        protected ConfigFieldPlan computeValue(Class<?> type) {
            return new ConfigFieldPlan(type);
        }
    };

    private final Map<Class<?>, List<DeclaredConfig.Property>> byType;
    private final Set<Field> descendable;
    private final Set<Field> inspected;
    private final Set<Field> inaccessible;
    private final Set<Field> finalFields;

    private ConfigFieldPlan(Class<?> rootType) {
        Builder builder = new Builder();

        synchronized (LiteConfig.codecs()) {
            builder.warm(rootType);
        }

        byType = Map.copyOf(builder.byType);
        descendable = Set.copyOf(builder.descendable);
        inspected = Set.copyOf(builder.inspected);
        inaccessible = Set.copyOf(builder.inaccessible);
        finalFields = Set.copyOf(builder.finalFields);
    }

    public static ConfigFieldPlan of(Class<?> rootType) {
        return PLANS.get(Objects.requireNonNull(rootType, "rootType"));
    }

    public List<DeclaredConfig.Property> properties(Class<?> type) {
        List<DeclaredConfig.Property> properties = byType.get(type);
        return properties != null
            ? properties
            : ConfigFieldPlan.of(type).properties(type);
    }

    public boolean descendable(DeclaredConfig.Property property) {
        Field field = property.field();
        return inspected.contains(field)
            ? descendable.contains(field)
            : ConfigFieldPlan.of(field.getDeclaringClass()).descendable(property);
    }

    public void requireAccessible(ConfigScope scope) {
        inaccessible.stream()
            .min(Comparator.comparing((Field field) -> field.getDeclaringClass().getName())
                .thenComparing(Field::getName))
            .ifPresent(field -> {
                throw scope.exception(
                    ConfigError.REFLECTION_ACCESS,
                    field.getName(),
                    field.getDeclaringClass().getName());
            });

        finalFields.stream()
            .min(Comparator.comparing((Field field) -> field.getDeclaringClass().getName())
                .thenComparing(Field::getName))
            .ifPresent(field -> {
                throw scope.exception(
                    ConfigError.FINAL_CONFIG_FIELD,
                    field.getName(),
                    field.getDeclaringClass().getName());
            });
    }

    private static final class Builder {

        private final Map<Class<?>, List<DeclaredConfig.Property>> byType = new HashMap<>();
        private final Set<Field> descendable = new HashSet<>();
        private final Set<Field> inspected = new HashSet<>();
        private final Set<Class<?>> warmed = new HashSet<>();
        private final Set<Field> inaccessible = new HashSet<>();
        private final Set<Field> finalFields = new HashSet<>();

        private void warm(Class<?> type) {
            if (!warmed.add(type)) {
                return;
            }

            for (DeclaredConfig.Property property : properties(type)) {
                if (isDescendable(property)) {
                    warm(property.type());
                }
            }
        }

        private List<DeclaredConfig.Property> properties(Class<?> type) {
            return byType.computeIfAbsent(type, this::propertiesOf);
        }

        private List<DeclaredConfig.Property> propertiesOf(Class<?> type) {
            inaccessible.addAll(PersistedFields.inaccessible(type));
            finalFields.addAll(PersistedFields.finalFields(type));

            List<DeclaredConfig.Property> properties = List.copyOf(DeclaredConfig.of(type).properties());
            properties.forEach(this::isDescendable);

            return properties;
        }

        private boolean isDescendable(DeclaredConfig.Property property) {
            Field field = property.field();
            if (!inspected.add(field)) {
                return descendable.contains(field);
            }

            LiteConfig.codecs().lockForPlanning(property.declaredType());
            if (PersistedFields.descendable(property.declaredType())) {
                descendable.add(field);
                return true;
            }

            return false;
        }
    }
}
