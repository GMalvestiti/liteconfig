package com.gmalvestiti.minecraft.liteconfig.reflection.listener;

import com.gmalvestiti.minecraft.liteconfig.exception.ConfigError;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigScope;
import com.gmalvestiti.minecraft.liteconfig.exception.LiteConfigException;
import com.gmalvestiti.minecraft.liteconfig.metadata.ConfigFieldPlan;
import com.gmalvestiti.minecraft.liteconfig.metadata.DeclaredConfig;
import com.gmalvestiti.minecraft.liteconfig.reflection.ConfigFieldAccess;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ConfigFieldListeners<T> {

    private final ConfigScope scope;
    private final ConfigFieldAccess fieldAccess;
    private final List<ConfigFieldListener> listeners;
    private volatile boolean closed;

    private ConfigFieldListeners(
        ConfigScope scope,
        ConfigFieldAccess fieldAccess,
        List<ConfigFieldListener> listeners
    ) {
        this.scope = scope;
        this.fieldAccess = fieldAccess;
        this.listeners = List.copyOf(listeners);
    }

    public static <T> ConfigFieldListeners<T> resolve(
        Class<T> rootType,
        ConfigFieldPlan fields,
        ConfigScope scope,
        ConfigFieldAccess fieldAccess
    ) {
        List<ConfigFieldListener> listeners = new ArrayList<>();
        collect(rootType, fields, new Field[0], new LinkedHashSet<>(), listeners, scope);
        return new ConfigFieldListeners<>(scope, fieldAccess, listeners);
    }

    public Runnable enqueueChanged(T oldState, T newState, boolean fromSync) {
        return () -> {
            if (!closed) {
                invoke(oldState, newState, fromSync);
            }
        };
    }

    public void close() {
        closed = true;
    }

    public Runnable enqueueSynced(
        T oldState,
        T newState,
        Runnable after
    ) {
        return () -> {
            if (!closed) {
                invoke(oldState, newState, true);
                after.run();
            }
        };
    }

    private void invoke(T oldState, T newState, boolean fromSync) {
        for (ConfigFieldListener listener : listeners) {
            try {
                listener.invokeIfChanged(
                    fieldAccess,
                    oldState,
                    newState,
                    fromSync);
            } catch (InvocationTargetException failure) {
                report(failure.getCause() == null ? failure : failure.getCause());
            } catch (IllegalAccessException | RuntimeException failure) {
                report(failure);
            }
        }
    }

    private static void collect(
        Class<?> ownerType,
        ConfigFieldPlan fields,
        Field[] prefix,
        Set<Class<?>> ancestors,
        List<ConfigFieldListener> listeners,
        ConfigScope scope
    ) {
        if (!ancestors.add(ownerType)) {
            return;
        }

        for (DeclaredConfig.Property property : fields.properties(ownerType)) {

            Field field = property.field();
            Field[] chain = Arrays.copyOf(prefix, prefix.length + 1);
            chain[chain.length - 1] = field;

            if (property.listenerProblem().isPresent()) {
                throw invalid(field, scope, property.listenerProblem().get());
            }

            if (property.listener().isPresent()) {
                listeners.add(new ConfigFieldListener(chain, property.listener().get()));
            }

            if (fields.descendable(property)) {
                collect(property.type(), fields, chain, ancestors, listeners, scope);
            }
        }

        ancestors.remove(ownerType);
    }

    private static LiteConfigException invalid(Field field, ConfigScope scope, String reason) {
        return scope.exception(
            ConfigError.INVALID_ENTRY_LISTENER,
            field.getName(),
            field.getDeclaringClass().getName(),
            reason
        );
    }

    private void report(Throwable failure) {
        if (failure instanceof Error error) {
            throw error;
        }

        LiteConfigException reported = scope.exception(
            ConfigError.CHANGE_LISTENER_FAILED, failure, String.valueOf(failure.getMessage()));

        scope.logError(reported.rawMessage(), failure);
    }
}
