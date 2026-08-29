package com.gmalvestiti.minecraft.liteconfig.reflection.callback;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CallbackQueueTest {

    @Test
    void testDrainsFiniteReentrantNotifications() {
        AtomicInteger invoked = new AtomicInteger();
        AtomicReference<CallbackQueue<Integer>> queue = new AtomicReference<>();
        queue.set(new CallbackQueue<>(
            new Object(),
            notification -> {
                if (invoked.incrementAndGet() < 3) {
                    queue.get().enqueue(notification).run();
                }
            },
            limit -> new IllegalStateException("limit " + limit)));

        queue.get().enqueue(notification(1)).run();

        assertEquals(3, invoked.get());
    }

    @Test
    void testBoundsReentrantNotificationsAndRecovers() {
        AtomicBoolean reentrant = new AtomicBoolean(true);
        AtomicInteger invoked = new AtomicInteger();
        AtomicReference<CallbackQueue<Integer>> queue = new AtomicReference<>();
        queue.set(new CallbackQueue<>(
            new Object(),
            notification -> {
                invoked.incrementAndGet();
                if (reentrant.get()) {
                    queue.get().enqueue(notification).run();
                }
            },
            limit -> new IllegalStateException("limit " + limit)));

        IllegalStateException failure = assertThrows(
            IllegalStateException.class,
            queue.get().enqueue(notification(1))::run);
        assertEquals(
            "limit " + CallbackQueue.MAX_NOTIFICATIONS_PER_DRAIN,
            failure.getMessage());

        reentrant.set(false);
        queue.get().enqueue(notification(2)).run();
        assertEquals(CallbackQueue.MAX_NOTIFICATIONS_PER_DRAIN + 1, invoked.get());
    }

    private static CallbackNotification<Integer> notification(int value) {
        return new CallbackNotification<>(value - 1, value, false);
    }
}
