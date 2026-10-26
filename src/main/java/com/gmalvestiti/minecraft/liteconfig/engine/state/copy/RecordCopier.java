package com.gmalvestiti.minecraft.liteconfig.engine.state.copy;

import com.gmalvestiti.minecraft.liteconfig.api.annotations.Ignore;
import com.gmalvestiti.minecraft.liteconfig.registry.ConfigCodecRegistry;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;

final class RecordCopier implements Copier {

    private final Class<?> type;
    private final Constructor<?> constructor;
    private final Field[] fields;
    private final Copier[] copiers;

    private RecordCopier(Class<?> type, Constructor<?> constructor, Field[] fields, Copier[] copiers) {
        this.type = type;
        this.constructor = constructor;
        this.fields = fields;
        this.copiers = copiers;
    }

    static boolean immutable(Class<?> type, ConfigCodecRegistry codecs) {
        for (RecordComponent component : type.getRecordComponents()) {
            if (!(component.getGenericType() instanceof Class<?> raw) || !Copiers.immutable(raw, codecs)) {
                return false;
            }
        }
        return true;
    }

    static Copier create(Class<?> type, ConfigCodecRegistry codecs) {
        RecordComponent[] components = type.getRecordComponents();
        Class<?>[] types = new Class<?>[components.length];
        Field[] fields = new Field[components.length];
        Copier[] copiers = new Copier[components.length];
        boolean shared = true;

        try {
            for (int i = 0; i < components.length; i++) {
                Field field = type.getDeclaredField(components[i].getName());

                if (field.isAnnotationPresent(Ignore.class) || !field.trySetAccessible()) {
                    return null;
                }

                types[i] = components[i].getType();
                fields[i] = field;
                copiers[i] = Copiers.create(components[i].getGenericType(), codecs);
                shared &= copiers[i] == SAME;
            }

            if (shared) {
                return SAME;
            }

            Constructor<?> constructor = type.getDeclaredConstructor(types);
            return constructor.trySetAccessible() ? new RecordCopier(type, constructor, fields, copiers) : null;
        } catch (NoSuchFieldException | NoSuchMethodException ex) {
            return null;
        }
    }

    @Override
    public Object copy(Object source) {
        Object[] arguments = new Object[fields.length];

        try {
            for (int i = 0; i < fields.length; i++) {
                Object value = fields[i].get(source);
                arguments[i] = value == null ? null : copiers[i].copy(value);
            }

            return constructor.newInstance(arguments);
        } catch (InvocationTargetException ex) {
            throw new IllegalStateException("Failed to create " + type.getName(), ex.getCause());
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Failed to copy " + type.getName(), ex);
        }
    }
}
