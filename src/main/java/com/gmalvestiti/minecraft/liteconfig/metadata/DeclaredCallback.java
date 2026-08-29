package com.gmalvestiti.minecraft.liteconfig.metadata;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

final class DeclaredCallback {

    private DeclaredCallback() {}

    static Resolution resolve(Field field, String name) {
        Class<?> expectedType = boxed(field.getType());

        for (Class<?> owner = field.getDeclaringClass();
            owner != null && owner != Object.class;
            owner = owner.getSuperclass()) {

            for (Method method : owner.getDeclaredMethods()) {
                if (!method.getName().equals(name) || !matches(method, expectedType)) {
                    continue;
                }

                if (!method.trySetAccessible()) {
                    return new Resolution(null, "the method is not accessible");
                }

                return new Resolution(method, null);
            }
        }

        return new Resolution(
            null,
            "expected an instance void %s(%s, %s, boolean) method"
                .formatted(name, expectedType.getName(), expectedType.getName())
        );
    }

    private static boolean matches(Method method, Class<?> expectedType) {
        Class<?>[] parameters = method.getParameterTypes();
        return method.getReturnType() == Void.TYPE
            && !Modifier.isStatic(method.getModifiers())
            && !Modifier.isAbstract(method.getModifiers())
            && parameters.length == 3
            && parameters[0] == expectedType
            && parameters[1] == expectedType
            && parameters[2] == Boolean.TYPE;
    }

    private static Class<?> boxed(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        return switch (type.getName()) {
            case "boolean" -> Boolean.class;
            case "byte" -> Byte.class;
            case "short" -> Short.class;
            case "char" -> Character.class;
            case "int" -> Integer.class;
            case "long" -> Long.class;
            case "float" -> Float.class;
            case "double" -> Double.class;
            default -> throw new IllegalArgumentException(
                "Unsupported primitive callback type: " + type);
        };
    }

    record Resolution(Method method, String problem) {}
}
