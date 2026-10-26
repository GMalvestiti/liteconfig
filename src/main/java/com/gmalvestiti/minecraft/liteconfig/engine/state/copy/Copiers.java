package com.gmalvestiti.minecraft.liteconfig.engine.state.copy;

import com.gmalvestiti.minecraft.liteconfig.network.codec.BuiltInTypes;
import com.gmalvestiti.minecraft.liteconfig.reflection.PersistedFields;
import com.gmalvestiti.minecraft.liteconfig.registry.ConfigCodecRegistry;
import com.gmalvestiti.minecraft.liteconfig.storage.ConfigSerialization;

import java.lang.reflect.Array;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.Collection;
import java.util.Map;
import java.util.function.IntFunction;

import static com.gmalvestiti.minecraft.liteconfig.engine.state.copy.Copier.SAME;

final class Copiers {

    private Copiers() {}

    static Copier create(Type type, ConfigCodecRegistry codecs) {
        if (codecs.find(type).isPresent()) {
            return roundTrip(type);
        }

        if (type instanceof Class<?> raw) {
            return forClass(raw, codecs);
        }

        if (type instanceof GenericArrayType array) {
            Type component = array.getGenericComponentType();
            if (component instanceof ParameterizedType parameterized
                && parameterized.getRawType() instanceof Class<?> raw) {
                return array(type, component, raw, codecs);
            }
            return roundTrip(type);
        }

        if (type instanceof ParameterizedType parameterized) {
            return forParameterized(parameterized, codecs);
        }

        return roundTrip(type);
    }

    static Copier roundTrip(Type type) {
        return value -> ConfigSerialization.copy(value, type);
    }

    static boolean immutable(Class<?> type, ConfigCodecRegistry codecs) {
        return (type.isPrimitive() || type.isEnum() || BuiltInTypes.isTextValue(type) || isBoxed(type))
            && codecs.find(type).isEmpty();
    }

    private static boolean isBoxed(Class<?> type) {
        return type == Boolean.class || type == Byte.class || type == Character.class || type == Short.class
            || type == Integer.class || type == Long.class || type == Float.class || type == Double.class
            || type == String.class;
    }

    private static Copier forClass(Class<?> raw, ConfigCodecRegistry codecs) {
        if (immutable(raw, codecs)) {
            return SAME;
        }
        if (raw.isArray()) {
            return array(raw, raw.getComponentType(), raw.getComponentType(), codecs);
        }
        if (raw.isRecord()) {
            return RecordCopier.immutable(raw, codecs) ? SAME : ClassCopier.of(raw);
        }
        if (PersistedFields.descendable(raw) && !raw.isInterface() && !Modifier.isAbstract(raw.getModifiers())) {
            ClassCopier nested = ClassCopier.of(raw);
            return value -> value.getClass() == raw ? nested.copy(value) : ConfigSerialization.copy(value, raw);
        }
        return roundTrip(raw);
    }

    private static Copier forParameterized(ParameterizedType type, ConfigCodecRegistry codecs) {
        Type[] arguments = type.getActualTypeArguments();
        IntFunction<?> factory = type.getRawType() instanceof Class<?> raw ? Containers.factory(raw, arguments) : null;

        if (factory != null && arguments.length == 1) {
            Type element = bound(arguments[0]);
            if (element != null) {
                return collection(factory, create(element, codecs));
            }
        } else if (factory != null && arguments.length == 2) {
            Type key = bound(arguments[0]);
            Type value = bound(arguments[1]);
            if (key != null && value != null && create(key, codecs) == SAME) {
                return map(factory, create(value, codecs));
            }
        }

        return roundTrip(type);
    }

    private static Copier array(Type type, Type component, Class<?> rawComponent, ConfigCodecRegistry codecs) {
        Copier element = create(component, codecs);

        if (element == SAME) {
            return value -> {
                int length = Array.getLength(value);
                Object target = Array.newInstance(rawComponent, length);
                System.arraycopy(value, 0, target, 0, length);
                return target;
            };
        }

        if (rawComponent.isPrimitive()) {
            return roundTrip(type);
        }

        return value -> {
            Object[] source = (Object[]) value;
            Object[] target = (Object[]) Array.newInstance(rawComponent, source.length);

            for (int i = 0; i < source.length; i++) {
                Object item = source[i];
                target[i] = item == null ? null : element.copy(item);
            }

            return target;
        };
    }

    @SuppressWarnings("unchecked")
    private static Copier collection(IntFunction<?> factory, Copier element) {
        if (element == SAME) {
            return value -> {
                Collection<?> source = (Collection<?>) value;
                Collection<Object> target = (Collection<Object>) factory.apply(source.size());
                target.addAll(source);
                return target;
            };
        }

        return value -> {
            Collection<?> source = (Collection<?>) value;
            Collection<Object> target = (Collection<Object>) factory.apply(source.size());

            for (Object item : source) {
                target.add(item == null ? null : element.copy(item));
            }

            return target;
        };
    }

    @SuppressWarnings("unchecked")
    private static Copier map(IntFunction<?> factory, Copier element) {
        if (element == SAME) {
            return value -> {
                Map<?, ?> source = (Map<?, ?>) value;
                Map<Object, Object> target = (Map<Object, Object>) factory.apply(source.size());
                target.putAll(source);
                return target;
            };
        }

        return value -> {
            Map<?, ?> source = (Map<?, ?>) value;
            Map<Object, Object> target = (Map<Object, Object>) factory.apply(source.size());

            for (Map.Entry<?, ?> entry : source.entrySet()) {
                Object item = entry.getValue();
                target.put(entry.getKey(), item == null ? null : element.copy(item));
            }

            return target;
        };
    }

    private static Type bound(Type argument) {
        if (argument instanceof WildcardType wildcard) {
            return wildcard.getLowerBounds().length == 0 ? wildcard.getUpperBounds()[0] : null;
        }
        return argument instanceof TypeVariable<?> ? null : argument;
    }
}
