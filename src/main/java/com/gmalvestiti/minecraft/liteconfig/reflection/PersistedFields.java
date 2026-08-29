package com.gmalvestiti.minecraft.liteconfig.reflection;

import com.gmalvestiti.minecraft.liteconfig.api.annotations.Ignore;
import com.gmalvestiti.minecraft.liteconfig.api.LiteConfig;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public final class PersistedFields {

    private static final ClassValue<Resolution> CACHE = new ClassValue<>() {

        @Override
        protected Resolution computeValue(Class<?> type) {

            List<Field> fields = new ArrayList<>();
            List<Field> inaccessible = new ArrayList<>();
            List<Field> finalFields = new ArrayList<>();

            for (Class<?> owner = type; owner != null && owner != Object.class; owner = owner.getSuperclass()) {

                Field[] declared = owner.getDeclaredFields();
                Arrays.sort(declared, Comparator.comparing(Field::getName));

                for (Field field : declared) {
                    if (isPersisted(field)) {
                        if (!field.trySetAccessible()) {
                            inaccessible.add(field);
                        }

                        if (Modifier.isFinal(field.getModifiers())) {
                            finalFields.add(field);
                        }

                        fields.add(field);
                    }
                }
            }

            return new Resolution(List.copyOf(fields), List.copyOf(inaccessible), List.copyOf(finalFields));
        }
    };

    private PersistedFields() {}

    public static List<Field> of(Class<?> type) {
        return CACHE.get(type).fields();
    }

    public static List<Field> inaccessible(Class<?> type) {
        return CACHE.get(type).inaccessible();
    }

    public static List<Field> finalFields(Class<?> type) {
        return CACHE.get(type).finalFields();
    }

    public static boolean descendable(Type type) {
        if (type == null || LiteConfig.codecs().find(type).isPresent()) {
            return false;
        }

        Class<?> rawType = switch (type) {
            case Class<?> classType -> classType;
            case ParameterizedType parameterized
                when parameterized.getRawType() instanceof Class<?> classType -> classType;
            default -> null;
        };

        return rawType != null
            && !rawType.isPrimitive()
            && !rawType.isArray()
            && !rawType.isEnum()
            && !rawType.getName().startsWith("java.")
            && !Collection.class.isAssignableFrom(rawType)
            && !Map.class.isAssignableFrom(rawType);
    }

    private static boolean isPersisted(Field field) {
        int modifiers = field.getModifiers();
        return !Modifier.isStatic(modifiers)
            && !Modifier.isTransient(modifiers)
            && !field.isSynthetic()
            && field.getAnnotation(Ignore.class) == null;
    }

    private record Resolution(List<Field> fields, List<Field> inaccessible, List<Field> finalFields) {}
}
