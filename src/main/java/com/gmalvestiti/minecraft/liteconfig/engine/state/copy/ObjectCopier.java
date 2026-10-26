package com.gmalvestiti.minecraft.liteconfig.engine.state.copy;

import com.gmalvestiti.minecraft.liteconfig.reflection.PersistedFields;
import com.gmalvestiti.minecraft.liteconfig.registry.ConfigCodecRegistry;
import com.gmalvestiti.minecraft.liteconfig.storage.ConfigSerialization;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;

final class ObjectCopier implements Copier {

    private static final byte REFERENCE = 0;
    private static final byte VALUE = 1;
    private static final byte BOOLEAN = 2;
    private static final byte BYTE = 3;
    private static final byte CHAR = 4;
    private static final byte SHORT = 5;
    private static final byte INT = 6;
    private static final byte LONG = 7;
    private static final byte FLOAT = 8;
    private static final byte DOUBLE = 9;

    private final Class<?> type;
    private final Constructor<?> constructor;
    private final Field[] fields;
    private final byte[] kinds;
    private final Copier[] copiers;
    private final Field[] ignored;

    ObjectCopier(Class<?> type, ConfigCodecRegistry codecs) {
        this.type = type;
        this.constructor = direct(type) ? constructor(type) : null;
        this.fields = constructor == null ? new Field[0] : PersistedFields.of(type).toArray(new Field[0]);
        this.kinds = new byte[fields.length];
        this.copiers = new Copier[fields.length];
        this.ignored = PersistedFields.ignored(type).toArray(new Field[0]);

        for (int i = 0; i < fields.length; i++) {
            Class<?> raw = fields[i].getType();

            if (raw.isPrimitive() && codecs.find(raw).isEmpty()) {
                kinds[i] = primitive(raw);
            } else {
                Copier copier = Copiers.create(fields[i].getGenericType(), codecs);
                kinds[i] = copier == SAME ? REFERENCE : VALUE;
                copiers[i] = copier;
            }
        }
    }

    @Override
    public Object copy(Object source) {
        try {
            Object target;

            if (constructor == null) {
                target = ConfigSerialization.copy(source, type);
                if (ignored.length == 0 || target == null || target.getClass() != type) {
                    return target;
                }
            } else {
                target = constructor.newInstance();
                copyFields(source, target);
            }

            for (Field field : ignored) {
                field.set(target, field.get(source));
            }

            return target;
        } catch (InvocationTargetException ex) {
            throw new IllegalStateException("Failed to create " + type.getName(), ex.getCause());
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Failed to copy " + type.getName(), ex);
        }
    }

    private void copyFields(Object source, Object target) throws IllegalAccessException {
        for (int i = 0; i < fields.length; i++) {
            Field field = fields[i];

            switch (kinds[i]) {
                case REFERENCE -> field.set(target, field.get(source));
                case VALUE -> {
                    Object value = field.get(source);
                    field.set(target, value == null ? null : copiers[i].copy(value));
                }
                case BOOLEAN -> field.setBoolean(target, field.getBoolean(source));
                case BYTE -> field.setByte(target, field.getByte(source));
                case CHAR -> field.setChar(target, field.getChar(source));
                case SHORT -> field.setShort(target, field.getShort(source));
                case INT -> field.setInt(target, field.getInt(source));
                case LONG -> field.setLong(target, field.getLong(source));
                case FLOAT -> field.setFloat(target, field.getFloat(source));
                default -> field.setDouble(target, field.getDouble(source));
            }
        }
    }

    private static byte primitive(Class<?> type) {
        if (type == boolean.class) return BOOLEAN;
        if (type == byte.class) return BYTE;
        if (type == char.class) return CHAR;
        if (type == short.class) return SHORT;
        if (type == int.class) return INT;
        if (type == long.class) return LONG;
        if (type == float.class) return FLOAT;
        return DOUBLE;
    }

    private static boolean direct(Class<?> type) {
        return !type.isInterface()
            && !Modifier.isAbstract(type.getModifiers())
            && !type.isAnonymousClass()
            && !type.isLocalClass()
            && PersistedFields.inaccessible(type).isEmpty();
    }

    private static Constructor<?> constructor(Class<?> type) {
        try {
            Constructor<?> constructor = type.getDeclaredConstructor();
            return constructor.trySetAccessible() ? constructor : null;
        } catch (NoSuchMethodException ex) {
            return null;
        }
    }
}
