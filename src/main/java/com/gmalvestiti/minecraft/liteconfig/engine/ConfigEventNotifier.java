package com.gmalvestiti.minecraft.liteconfig.engine;

import com.gmalvestiti.minecraft.liteconfig.api.ConfigSubscription;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigError;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigScope;
import com.gmalvestiti.minecraft.liteconfig.exception.LiteConfigException;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public final class ConfigEventNotifier<T> {

    private final ConfigScope scope;
    private final Object monitor;
    private final List<Subscription<T>> updateEvents = new CopyOnWriteArrayList<>();
    private final List<Subscription<T>> loadEvents = new CopyOnWriteArrayList<>();
    private final List<Subscription<T>> saveEvents = new CopyOnWriteArrayList<>();
    private volatile boolean closed;

    public ConfigEventNotifier(ConfigScope scope, Object monitor) {
        this.scope = scope;
        this.monitor = Objects.requireNonNull(monitor, "monitor");
    }

    public ConfigSubscription addUpdateEvent(Consumer<T> listener) {
        return add(updateEvents, listener);
    }

    public ConfigSubscription addLoadEvent(Consumer<T> listener) {
        return add(loadEvents, listener);
    }

    public ConfigSubscription addSaveEvent(Consumer<T> listener) {
        return add(saveEvents, listener);
    }

    public void notifyUpdated(T state) {
        dispatch(updateEvents, state);
    }

    public void notifyLoaded(T state) {
        dispatch(loadEvents, state);
    }

    public void notifySaved(T state) {
        dispatch(saveEvents, state);
    }

    public void close() {
        synchronized (monitor) {
            closed = true;
            for (List<Subscription<T>> listeners : List.of(updateEvents, loadEvents, saveEvents)) {
                listeners.forEach(this::remove);
            }
        }
    }

    private ConfigSubscription add(List<Subscription<T>> listeners, Consumer<T> listener) {
        if (closed) {
            return ConfigSubscription.NONE;
        }

        Subscription<T> subscription = new Subscription<>(this, listeners,
            Objects.requireNonNull(listener, "listener"));
        listeners.add(subscription);

        return subscription;
    }

    private void remove(Subscription<T> subscription) {
        subscription.listener = null;
        subscription.listeners.remove(subscription);
        subscription.listeners = null;
        subscription.notifier = null;
    }

    private void dispatch(List<Subscription<T>> listeners, T state) {
        for (Subscription<T> subscription : listeners) {
            Consumer<T> listener = subscription.listener;
            if (listener != null) {
                try {
                    listener.accept(state);
                } catch (RuntimeException failure) {
                    LiteConfigException reported = scope.exception(
                        ConfigError.CHANGE_LISTENER_FAILED, failure, String.valueOf(failure));
                    scope.logError(reported.rawMessage(), failure);
                }
            }
        }
    }

    private static final class Subscription<T> implements ConfigSubscription {
        private volatile ConfigEventNotifier<T> notifier;
        private List<Subscription<T>> listeners;
        private volatile Consumer<T> listener;

        private Subscription(
            ConfigEventNotifier<T> notifier,
            List<Subscription<T>> listeners,
            Consumer<T> listener
        ) {
            this.notifier = notifier;
            this.listeners = listeners;
            this.listener = listener;
        }

        @Override
        public void close() {
            ConfigEventNotifier<T> current = notifier;
            if (current != null) {
                synchronized (current.monitor) {
                    if (notifier != null) {
                        current.remove(this);
                    }
                }
            }
        }
    }
}
