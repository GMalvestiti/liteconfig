package com.gmalvestiti.minecraft.liteconfig.async;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConfigExecutorsTest {

    @Test
    void testReturnsSingletonExecutorAndExecutesTasks() throws Exception {
        Executor first = ConfigExecutors.defaultExecutor();
        Executor second = ConfigExecutors.defaultExecutor();
        assertSame(first, second);

        CompletableFuture<String> future = CompletableFuture.supplyAsync(
            () -> Thread.currentThread().getName(),
            first
        );
        String threadName = future.get(5, TimeUnit.SECONDS);
        assertTrue(threadName.contains("liteconfig-io"));
    }

    @Test
    void testHidesTheExecutorServiceSoCallersCannotStopTheSharedWorker() {
        assertFalse(ConfigExecutors.defaultExecutor() instanceof ExecutorService);
    }

    @Test
    void testCreatesWorkerWithUncaughtExceptionHandler() throws Exception {
        Thread worker = (Thread) newWorker(() -> {
        });

        assertTrue(worker.isDaemon());
        assertTrue(worker.getName().contains("liteconfig-io"));
        worker.getUncaughtExceptionHandler().uncaughtException(worker, new RuntimeException("boom"));
    }

    @Test
    void testMarksTheWorkerOnlyOnceItStartsRunning() throws Exception {
        Thread neverStarted = (Thread) newWorker(() -> {
        });

        assertFalse(ConfigExecutors.isWorkerThread());

        CompletableFuture<Boolean> insideTask =
            CompletableFuture.supplyAsync(ConfigExecutors::isWorkerThread, ConfigExecutors.defaultExecutor());

        assertTrue(insideTask.get(5, TimeUnit.SECONDS),
            "a task on the shared worker must see itself as inside a config task");
        assertFalse(neverStarted.isAlive(),
            "constructing a worker must not claim the marker for a thread that never runs");
        assertFalse(ConfigExecutors.isWorkerThread(),
            "the calling thread must never be seen as the config worker");
    }

    @Test
    void testDoesNotBlockAnUnrelatedSerialQueue() throws Exception {
        ConfigTaskQueue blocked = ConfigExecutors.newSerialQueue();
        ConfigTaskQueue independent = ConfigExecutors.newSerialQueue();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        CompletableFuture<Void> first = blocked.submit(() -> {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(failure);
            }
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));

        assertTrue(independent.submit(() -> true).get(5, TimeUnit.SECONDS));
        release.countDown();
        first.get(5, TimeUnit.SECONDS);
    }

    @Test
    void testCompletesQueuedFuturesWhenTheBackendStopsAcceptingTasks() {
        AtomicInteger submissions = new AtomicInteger();
        ConfigTaskQueue queue = new ConfigTaskQueue(command -> {
            if (submissions.getAndIncrement() > 0) {
                throw new java.util.concurrent.RejectedExecutionException();
            }
            command.run();
        });
        AtomicReference<CompletableFuture<Integer>> queued = new AtomicReference<>();

        queue.submit(() -> queued.set(queue.submit(() -> 42))).join();

        assertTrue(queued.get().isDone());
        assertEquals(42, queued.get().join());
    }

    @Test
    void testRejectsEveryQueuedFutureWhenInitialSchedulingFails() throws Exception {
        CountDownLatch scheduling = new CountDownLatch(1);
        CountDownLatch reject = new CountDownLatch(1);
        ConfigTaskQueue queue = new ConfigTaskQueue(command -> {
            scheduling.countDown();
            try {
                reject.await();
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(failure);
            }
            throw new java.util.concurrent.RejectedExecutionException();
        });
        AtomicReference<CompletableFuture<Integer>> first = new AtomicReference<>();
        CompletableFuture<Void> firstSubmission = CompletableFuture.runAsync(
            () -> first.set(queue.submit(() -> 1)));
        assertTrue(scheduling.await(5, TimeUnit.SECONDS));
        CompletableFuture<Integer> second = queue.submit(() -> 2);

        reject.countDown();
        firstSubmission.get(5, TimeUnit.SECONDS);

        assertThrows(CompletionException.class, first.get()::join);
        assertThrows(CompletionException.class, second::join);
    }

    @Test
    void testRejectsWorkBeyondTheQueueCapacity() {
        ArrayDeque<Runnable> backend = new ArrayDeque<>();
        ConfigTaskQueue queue = new ConfigTaskQueue(backend::addLast, 1);

        CompletableFuture<Integer> running = queue.submit(() -> 1);
        CompletableFuture<Integer> queued = queue.submit(() -> 2);
        CompletableFuture<Integer> rejected = queue.submit(() -> 3);

        assertThrows(CompletionException.class, rejected::join);
        backend.removeFirst().run();
        backend.removeFirst().run();
        assertEquals(1, running.join());
        assertEquals(2, queued.join());
    }

    @Test
    void testAcceptsTerminalWorkBeyondTheQueueCapacity() {
        ArrayDeque<Runnable> backend = new ArrayDeque<>();
        ConfigTaskQueue queue = new ConfigTaskQueue(backend::addLast, 1);
        AtomicInteger executions = new AtomicInteger();

        queue.submit(executions::incrementAndGet);
        queue.submit(executions::incrementAndGet);
        CompletableFuture<Void> terminal = queue.submitTerminal(executions::incrementAndGet);

        while (!backend.isEmpty()) {
            backend.removeFirst().run();
        }

        terminal.join();
        assertEquals(3, executions.get());
    }

    @Test
    void testSkipsCancelledWorkBeforeExecution() {
        ArrayDeque<Runnable> backend = new ArrayDeque<>();
        AtomicInteger executions = new AtomicInteger();
        ConfigTaskQueue queue = new ConfigTaskQueue(backend::addLast);

        CompletableFuture<Integer> cancelled = queue.submit(executions::incrementAndGet);
        assertTrue(cancelled.cancel(false));
        backend.removeFirst().run();

        assertEquals(0, executions.get());
    }

    @Test
    void testDrainsRejectedBacklogWithoutRecursion() {
        ArrayDeque<Runnable> backend = new ArrayDeque<>();
        AtomicInteger submissions = new AtomicInteger();
        ConfigTaskQueue queue = new ConfigTaskQueue(command -> {
            if (submissions.getAndIncrement() > 0) {
                throw new java.util.concurrent.RejectedExecutionException();
            }
            backend.addLast(command);
        }, 2_000);
        AtomicInteger executions = new AtomicInteger();

        for (int task = 0; task < 2_000; task++) {
            queue.execute(executions::incrementAndGet);
        }
        backend.removeFirst().run();

        assertEquals(2_000, executions.get());
    }

    private static Object newWorker(Runnable task) throws Exception {
        Method factory = ConfigExecutors.class.getDeclaredMethod("newWorker", Runnable.class);
        factory.setAccessible(true);
        return factory.invoke(null, task);
    }
}
