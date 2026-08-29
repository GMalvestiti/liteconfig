package com.gmalvestiti.minecraft.liteconfig.async;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Serializes one config registration's work without dedicating a thread to it.
 */
public final class ConfigTaskQueue implements Executor {

    private static final int DEFAULT_MAX_PENDING_TASKS = 256;
    private static final ThreadLocal<ConfigTaskQueue> CURRENT = new ThreadLocal<>();

    private final Executor backend;
    private final int maxPendingTasks;
    private final ArrayDeque<QueuedTask> pending = new ArrayDeque<>();
    private boolean running;

    ConfigTaskQueue(Executor backend) {
        this(backend, DEFAULT_MAX_PENDING_TASKS);
    }

    ConfigTaskQueue(Executor backend, int maxPendingTasks) {
        this.backend = Objects.requireNonNull(backend, "backend");

        if (maxPendingTasks < 1) {
            throw new IllegalArgumentException("maxPendingTasks must be positive");
        }

        this.maxPendingTasks = maxPendingTasks;
    }

    @Override
    public void execute(Runnable command) {
        enqueue(new QueuedTask(
            Objects.requireNonNull(command, "command"),
            ignored -> {},
            () -> false));
    }

    private void enqueue(QueuedTask task) {
        enqueue(task, true);
    }

    private void enqueue(QueuedTask task, boolean enforceCapacity) {
        synchronized (pending) {
            if (enforceCapacity && pending.size() >= maxPendingTasks) {
                throw new RejectedExecutionException("Config task queue capacity of " + maxPendingTasks + " was exceeded");
            }

            pending.addLast(task);

            if (running) {
                return;
            }

            running = true;
        }
        scheduleNext();
    }

    public <V> CompletableFuture<V> submit(Supplier<V> task) {
        Objects.requireNonNull(task, "task");
        CompletableFuture<V> completion = new CompletableFuture<>();
        try {
            enqueue(new QueuedTask(() -> {
                try {
                    completion.complete(task.get());
                } catch (Throwable failure) {
                    completion.completeExceptionally(failure);
                }
            }, completion::completeExceptionally, completion::isCancelled));
        } catch (RejectedExecutionException failure) {
            completion.completeExceptionally(failure);
        }
        return completion;
    }

    public CompletableFuture<Void> submit(Runnable task) {
        return submit(() -> {
            task.run();
            return null;
        });
    }

    public CompletableFuture<Void> submitTerminal(Runnable task) {
        CompletableFuture<Void> completion = new CompletableFuture<>();

        try {
            enqueue(new QueuedTask(() -> {
                try {
                    task.run();
                    completion.complete(null);
                } catch (Throwable failure) {
                    completion.completeExceptionally(failure);
                }
            }, completion::completeExceptionally, () -> false), false);
        } catch (RejectedExecutionException failure) {
            completion.completeExceptionally(failure);
        }

        return completion;
    }

    public boolean isCurrentThread() {
        return CURRENT.get() == this;
    }

    static boolean isConfigThread() {
        return CURRENT.get() != null;
    }

    private void scheduleNext() {
        QueuedTask next = pollNext();
        if (next == null) {
            return;
        }

        try {
            backend.execute(() -> {
                ConfigTaskQueue previous = CURRENT.get();
                CURRENT.set(this);
                try {
                    if (!next.cancelled()) {
                        next.command().run();
                    }
                } finally {
                    try {
                        scheduleNext();
                    } finally {
                        if (previous == null) {
                            CURRENT.remove();
                        } else {
                            CURRENT.set(previous);
                        }
                    }
                }
            });
        } catch (RejectedExecutionException failure) {
            if (isCurrentThread()) {
                drainInline(next);
                return;
            }

            synchronized (pending) {
                next.reject(failure);

                QueuedTask dropped;
                while ((dropped = pending.pollFirst()) != null) {
                    dropped.reject(failure);
                }

                running = false;
            }
            throw failure;
        }
    }

    private QueuedTask pollNext() {
        synchronized (pending) {
            QueuedTask next;

            while ((next = pending.pollFirst()) != null) {
                if (!next.cancelled()) {
                    return next;
                }
            }

            running = false;
            return null;
        }
    }

    private void drainInline(QueuedTask first) {
        QueuedTask current = first;
        Throwable firstFailure = null;

        while (current != null) {
            try {
                if (!current.cancelled()) {
                    current.command().run();
                }
            } catch (Throwable failure) {
                if (firstFailure == null) {
                    firstFailure = failure;
                } else {
                    firstFailure.addSuppressed(failure);
                }
            }
            current = pollNext();
        }

        if (firstFailure != null) {
            ConfigTaskQueue.<RuntimeException>rethrow(firstFailure);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void rethrow(Throwable failure) throws T {
        throw (T) failure;
    }

    private record QueuedTask(
        Runnable command,
        Consumer<RejectedExecutionException> rejection,
        BooleanSupplier cancellation
    ) {
        private void reject(RejectedExecutionException failure) {
            rejection.accept(failure);
        }

        private boolean cancelled() {
            return cancellation.getAsBoolean();
        }
    }
}
