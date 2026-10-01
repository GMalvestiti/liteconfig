package com.gmalvestiti.minecraft.liteconfig.engine;

import com.gmalvestiti.minecraft.liteconfig.api.ConfigSubscription;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigError;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigScope;
import com.gmalvestiti.minecraft.liteconfig.exception.LiteConfigException;

import java.util.List;
import java.lang.ref.WeakReference;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ConfigEventNotifier<T> {

    private final ConfigScope scope;
    private final List<Listener<T>> updateListeners = new CopyOnWriteArrayList<>();
    private final List<Listener<T>> loadListeners = new CopyOnWriteArrayList<>();
    private final List<Listener<T>> saveListeners = new CopyOnWriteArrayList<>();

    public ConfigEventNotifier(ConfigScope scope) {
        this.scope = scope;
    }

    public ConfigSubscription addUpdateListener(Consumer<T> listener, Executor executor) {
        return add(updateListeners, listener, executor, false);
    }

    public ConfigSubscription addUpdateListener(Consumer<T> listener) {
        return addUpdateListener(listener, Runnable::run);
    }

    public ConfigSubscription addLocalUpdateListener(Consumer<T> listener, Executor executor) {
        return add(updateListeners, listener, executor, true);
    }

    public ConfigSubscription addLoadListener(Consumer<T> listener, Executor executor) {
        return add(loadListeners, listener, executor, false);
    }

    public ConfigSubscription addSaveListener(Consumer<T> listener, Executor executor) {
        return add(saveListeners, listener, executor, false);
    }

    public void notifyUpdated(T state) {
        dispatch(updateListeners, state, false);
    }

    public void notifySynced(T state) {
        dispatch(updateListeners, state, true);
    }

    public void notifyLoaded(T state) {
        dispatch(loadListeners, state, false);
    }

    public void notifySaved(T state) {
        dispatch(saveListeners, state, false);
    }

    private ConfigSubscription add(
        List<Listener<T>> listeners,
        Consumer<T> listener,
        Executor executor,
        boolean localOnly
    ) {
        Listener<T> registered = new Listener<>(
            Objects.requireNonNull(listener, "listener"),
            Objects.requireNonNull(executor, "executor"), localOnly);

        listeners.add(registered);

        return ConfigSubscriptions.managed(() -> {
            registered.close();
            listeners.remove(registered);
        }, listener);
    }

    private void dispatch(List<Listener<T>> listeners, T state, boolean fromSync) {
        for (Listener<T> registered : listeners) {
            if (fromSync && registered.localOnly) {
                continue;
            }

            Consumer<T> listener = registered.listener();
            if (listener == null) {
                listeners.remove(registered);
                continue;
            }

            try {
                registered.executor().execute(() -> {
                    Consumer<T> current = registered.listener();
                    if (current != null) {
                        invoke(current, state);
                    }
                });
            } catch (RuntimeException ex) {
                report(ex);
            }
        }
    }

    private void invoke(Consumer<T> listener, T state) {
        try {
            listener.accept(state);
        } catch (RuntimeException ex) {
            report(ex);
        }
    }

    private void report(RuntimeException ex) {
        LiteConfigException failure = scope.exception(ConfigError.CHANGE_LISTENER_FAILED, ex, String.valueOf(ex));
        scope.logError(failure.rawMessage(), ex);
    }

    private static final class Listener<T> {

        private final WeakReference<Consumer<T>> reference;
        private final Executor executor;
        private final boolean localOnly;
        private final AtomicBoolean subscribed = new AtomicBoolean(true);

        private Listener(Consumer<T> listener, Executor executor, boolean localOnly) {
            this.reference = new WeakReference<>(listener);
            this.executor = executor;
            this.localOnly = localOnly;
        }

        private Consumer<T> listener() {
            return subscribed.get() ? reference.get() : null;
        }

        private Executor executor() {
            return executor;
        }

        private void close() {
            subscribed.set(false);
            reference.clear();
        }
    }

}
