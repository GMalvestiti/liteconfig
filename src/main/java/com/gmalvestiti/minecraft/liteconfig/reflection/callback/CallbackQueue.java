package com.gmalvestiti.minecraft.liteconfig.reflection.callback;

import java.util.ArrayDeque;
import java.util.function.IntFunction;
import java.util.function.Consumer;

final class CallbackQueue<T> {

    static final int MAX_NOTIFICATIONS_PER_DRAIN = 1_024;

    private final Object executionLock;
    private final Consumer<CallbackNotification<T>> callback;
    private final IntFunction<? extends RuntimeException> overflow;
    private final Object lock = new Object();
    private final ArrayDeque<CallbackNotification<T>> pending = new ArrayDeque<>();
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
        synchronized (lock) {
            pending.addLast(notification);
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

        boolean completed = false;
        int processed = 0;
        try {
            while (true) {
                CallbackNotification<T> notification;

                synchronized (lock) {
                    if (processed == MAX_NOTIFICATIONS_PER_DRAIN && !pending.isEmpty()) {
                        throw overflow.apply(MAX_NOTIFICATIONS_PER_DRAIN);
                    }

                    notification = pending.pollFirst();

                    if (notification == null) {
                        notifying = false;
                        completed = true;
                        return;
                    }
                }

                synchronized (executionLock) {
                    callback.accept(notification);
                }

                processed++;
            }
        } finally {
            if (!completed) {
                synchronized (lock) {
                    notifying = false;
                    pending.clear();
                }
            }
        }
    }
}
