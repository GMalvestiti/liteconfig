package com.gmalvestiti.minecraft.liteconfig.reflection.callback;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

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

    @Test
    void testSwitchesExecutorAfterAnExistingWorkerDrain() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        CallbackQueue<Integer> queue = new CallbackQueue<>(
            new Object(),
            notification -> {
                if (notification.newState() == 1) {
                    started.countDown();
                    await(release);
                }
                events.add(Thread.currentThread().getName());
            },
            limit -> new IllegalStateException("limit " + limit));

        ExecutorService worker = Executors.newSingleThreadExecutor(task -> new Thread(task, "test-worker"));
        ExecutorService client = Executors.newSingleThreadExecutor(task -> new Thread(task, "test-client-main"));
        try {
            java.util.concurrent.Future<?> drain = worker.submit(queue.enqueue(notification(1)));
            assertTrue(started.await(5, TimeUnit.SECONDS));

            queue.enqueue(notification(2), client,
                () -> events.add("notified-" + Thread.currentThread().getName())).run();
            release.countDown();
            drain.get(5, TimeUnit.SECONDS);
            client.submit(() -> {}).get(5, TimeUnit.SECONDS);

            assertEquals(List.of("test-worker", "test-client-main", "notified-test-client-main"), events);
        } finally {
            release.countDown();
            worker.shutdownNow();
            client.shutdownNow();
        }
    }

    @Test
    void testRecoversAfterExecutorRejection() {
        AtomicInteger invoked = new AtomicInteger();
        CallbackQueue<Integer> queue = new CallbackQueue<>(
            new Object(), notification -> invoked.incrementAndGet(),
            limit -> new IllegalStateException("limit " + limit));

        assertThrows(RejectedExecutionException.class, () -> queue.enqueue(
            notification(1), task -> { throw new RejectedExecutionException("stopped"); },
            null).run());

        queue.enqueue(notification(2)).run();

        assertEquals(1, invoked.get());
    }

    @Test
    void testClosingCancelsScheduledAndPendingNotifications() throws Exception {
        ArrayDeque<Runnable> executor = new ArrayDeque<>();
        AtomicInteger invoked = new AtomicInteger();
        CallbackQueue<Integer> queue = new CallbackQueue<>(
            new Object(), notification -> invoked.incrementAndGet(),
            limit -> new IllegalStateException("limit " + limit));
        queue.enqueue(notification(1), executor::addLast, invoked::incrementAndGet).run();
        queue.enqueue(notification(2)).run();

        queue.close();

        Field scheduled = CallbackQueue.class.getDeclaredField("scheduled");
        scheduled.setAccessible(true);
        assertNull(scheduled.get(queue));
        executor.removeFirst().run();
        assertEquals(0, invoked.get());
        assertThrows(IllegalStateException.class, () -> queue.enqueue(notification(3)));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Worker callback was not released");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }
}
