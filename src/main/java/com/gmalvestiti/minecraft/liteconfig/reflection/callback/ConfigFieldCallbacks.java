package com.gmalvestiti.minecraft.liteconfig.reflection.callback;

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

public final class ConfigFieldCallbacks<T> {

    private final ConfigScope scope;
    private final ConfigFieldAccess fieldAccess;
    private final List<ConfigFieldCallback> callbacks;
    private final CallbackQueue<T> notifications;

    private ConfigFieldCallbacks(
        ConfigScope scope,
        ConfigFieldAccess fieldAccess,
        List<ConfigFieldCallback> callbacks
    ) {
        this.scope = scope;
        this.fieldAccess = fieldAccess;
        this.callbacks = List.copyOf(callbacks);

        this.notifications = new CallbackQueue<>(
            new Object(),
            this::invoke,
            limit -> scope.exception(ConfigError.CHANGE_LISTENER_REENTRANCY_LIMIT, limit));
    }

    public static <T> ConfigFieldCallbacks<T> resolve(
        Class<T> rootType,
        ConfigFieldPlan fields,
        ConfigScope scope,
        ConfigFieldAccess fieldAccess
    ) {
        List<ConfigFieldCallback> callbacks = new ArrayList<>();
        collect(rootType, fields, new Field[0], new LinkedHashSet<>(), callbacks, scope);
        return new ConfigFieldCallbacks<>(scope, fieldAccess, callbacks);
    }

    public Runnable enqueueChanged(T oldState, T newState, boolean fromSync) {
        return notifications.enqueue(new CallbackNotification<>(oldState, newState, fromSync));
    }

    private void invoke(CallbackNotification<T> notification) {
        if (callbacks.isEmpty()) {
            return;
        }

        for (ConfigFieldCallback callback : callbacks) {
            try {
                callback.invokeIfChanged(
                    fieldAccess,
                    notification.oldState(),
                    notification.newState(),
                    notification.fromSync());
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
        List<ConfigFieldCallback> callbacks,
        ConfigScope scope
    ) {
        if (!ancestors.add(ownerType)) {
            return;
        }

        for (DeclaredConfig.Property property : fields.properties(ownerType)) {

            Field field = property.field();
            Field[] chain = Arrays.copyOf(prefix, prefix.length + 1);
            chain[chain.length - 1] = field;

            if (property.callbackProblem().isPresent()) {
                throw invalid(field, scope, property.callbackProblem().get());
            }

            if (property.callback().isPresent()) {
                callbacks.add(new ConfigFieldCallback(chain, property.callback().get()));
            }

            if (fields.descendable(property)) {
                collect(property.type(), fields, chain, ancestors, callbacks, scope);
            }
        }

        ancestors.remove(ownerType);
    }

    private static LiteConfigException invalid(Field field, ConfigScope scope, String reason) {
        return scope.exception(
            ConfigError.INVALID_ENTRY_ON_SET_CALLBACK,
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
