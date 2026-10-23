package com.gmalvestiti.minecraft.liteconfig.engine;

import com.gmalvestiti.minecraft.liteconfig.exception.ConfigError;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigScope;
import com.gmalvestiti.minecraft.liteconfig.exception.LiteConfigException;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class ConfigSyncNotifier<T> {

    private final ConfigScope scope;
    private final Consumer<T> clientSyncNotifier;
    private final AtomicReference<LocalChangeListener> localChangeListener = new AtomicReference<>();

    public ConfigSyncNotifier(ConfigScope scope, Consumer<T> clientSyncNotifier) {
        this.scope = Objects.requireNonNull(scope, "scope");
        this.clientSyncNotifier = Objects.requireNonNull(clientSyncNotifier, "clientSyncNotifier");
    }

    public void notifyLocalChange() {
        LocalChangeListener listener = localChangeListener.get();
        if (listener == null) {
            return;
        }

        try {
            listener.action().run();
        } catch (RuntimeException failure) {
            LiteConfigException reported = scope.exception(
                ConfigError.CHANGE_LISTENER_FAILED, failure, String.valueOf(failure));
            scope.logError(reported.rawMessage(), failure);
        }
    }

    public void notifyClientSync(T state) {
        clientSyncNotifier.accept(state);
    }

    public void setLocalChangeListener(Object owner, Runnable action) {
        LocalChangeListener next = new LocalChangeListener(
            Objects.requireNonNull(owner, "owner"),
            Objects.requireNonNull(action, "action"));

        localChangeListener.updateAndGet(current -> {
            if (current != null && current.owner() != owner) {
                throw new IllegalStateException("A config sync listener is already registered");
            }
            return next;
        });
    }

    public void clearLocalChangeListener(Object owner) {
        localChangeListener.updateAndGet(current ->
            current != null && current.owner() == owner ? null : current);
    }

    private record LocalChangeListener(Object owner, Runnable action) {}
}
