package com.gmalvestiti.minecraft.liteconfig.reflection.callback;

import java.util.ArrayDeque;
import java.util.concurrent.Executor;
import java.util.function.IntFunction;
import java.util.function.Consumer;

final class CallbackQueue<T> {

    static final int MAX_NOTIFICATIONS_PER_DRAIN = 1_024;

    private final Object executionLock;
    private final Consumer<CallbackNotification<T>> callback;
    private final IntFunction<? extends RuntimeException> overflow;
    private final Object lock = new Object();
    private final ArrayDeque<QueuedNotification<T>> pending = new ArrayDeque<>();
    private boolean notifying;

    CallbackQueue(
        Object executionLock,
        Consumer<CallbackNotification<T>> callback,
        IntFunction<? extends RuntimeException> overflow
    ) {
        this.executionLock = executionLock;
        this.callback = callback;
        this.overflow = overflow;
    }

    Runnable enqueue(CallbackNotification<T> notification) {
        return enqueue(notification, null, null);
    }

    Runnable enqueue(
        CallbackNotification<T> notification,
        Executor executor,
        Runnable after
    ) {
        synchronized (lock) {
            pending.addLast(new QueuedNotification<>(notification, executor, after));
        }
        return this::drain;
    }

    private void drain() {
        synchronized (lock) {
            if (notifying) {
                return;
            }
            notifying = true;
        }

        drain(0, null);
    }

    private void drain(int processed, Executor executor) {
        try {
            while (true) {
                QueuedNotification<T> notification;

                synchronized (lock) {
                    if (processed == MAX_NOTIFICATIONS_PER_DRAIN && !pending.isEmpty()) {
                        throw overflow.apply(MAX_NOTIFICATIONS_PER_DRAIN);
                    }

                    notification = pending.pollFirst();

                    if (notification == null) {
                        notifying = false;
                        return;
                    }
                }

                if (notification.executor() != null && notification.executor() != executor) {
                    int count = processed;

                    notification.executor().execute(() -> {
                        try {
                            invoke(notification);
                            drain(count + 1, notification.executor());
                        } catch (RuntimeException | Error failure) {
                            clear();
                            throw failure;
                        }
                    });

                    return;
                }

                invoke(notification);
                processed++;
            }
        } catch (RuntimeException | Error failure) {
            clear();
            throw failure;
        }
    }

    private void invoke(QueuedNotification<T> notification) {
        synchronized (executionLock) {
            callback.accept(notification.value());
            if (notification.after() != null) {
                notification.after().run();
            }
        }
    }

    private void clear() {
        synchronized (lock) {
            notifying = false;
            pending.clear();
        }
    }

    private record QueuedNotification<T>(
        CallbackNotification<T> value,
        Executor executor,
        Runnable after
    ) {}
}
