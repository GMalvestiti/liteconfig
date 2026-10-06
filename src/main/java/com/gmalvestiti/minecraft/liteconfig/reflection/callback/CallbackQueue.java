package com.gmalvestiti.minecraft.liteconfig.reflection.callback;

import java.util.ArrayDeque;
import java.lang.ref.WeakReference;
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
    private boolean closed;
    private QueuedNotification<T> scheduled;

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
            if (closed) {
                throw new IllegalStateException("Config callback queue is closed");
            }
            pending.addLast(new QueuedNotification<>(notification, executor, after));
        }
        return this::drain;
    }

    private void drain() {
        synchronized (lock) {
            if (closed || notifying) {
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
                    if (closed) {
                        return;
                    }

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

                    WeakReference<CallbackQueue<T>> owner = new WeakReference<>(this);
                    WeakReference<QueuedNotification<T>> queued = new WeakReference<>(notification);

                    synchronized (lock) {
                        if (closed) {
                            return;
                        }
                        scheduled = notification;
                    }

                    notification.executor().execute(() -> {
                        CallbackQueue<T> current = owner.get();
                        QueuedNotification<T> next = queued.get();

                        if (current != null && next != null) {
                            current.resume(next, count);
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
            synchronized (lock) {
                if (closed) {
                    return;
                }
            }

            callback.accept(notification.value());

            if (notification.after() != null) {
                notification.after().run();
            }
        }
    }

    private void clear() {
        synchronized (lock) {
            notifying = false;
            scheduled = null;
            pending.clear();
        }
    }

    void close() {
        synchronized (lock) {
            closed = true;
            clear();
        }
    }

    private void resume(QueuedNotification<T> notification, int processed) {
        try {
            synchronized (lock) {
                if (closed || scheduled != notification) {
                    return;
                }
                scheduled = null;
            }

            invoke(notification);
            drain(processed + 1, notification.executor());
        } catch (RuntimeException | Error failure) {
            clear();
            throw failure;
        }
    }

    private record QueuedNotification<T>(
        CallbackNotification<T> value,
        Executor executor,
        Runnable after
    ) {}
}
