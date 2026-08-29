package com.gmalvestiti.minecraft.liteconfig.async;

import com.gmalvestiti.minecraft.liteconfig.api.ConfigSide;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

public final class ConfigEventExecutors {

    private static final AtomicReference<Binding> SERVER_MAIN_THREAD = new AtomicReference<>();
    private static final AtomicReference<Binding> CLIENT_MAIN_THREAD = new AtomicReference<>();

    private static final Executor SERVER_THREAD =
        task -> executeOn("server", SERVER_MAIN_THREAD, task);
    private static final Executor CLIENT_THREAD =
        task -> executeOn("client", CLIENT_MAIN_THREAD, task);

    private ConfigEventExecutors() {
    }

    public static Executor logicalThread(ConfigSide side) {
        return switch (side) {
            case SERVER -> SERVER_THREAD;
            case CLIENT -> CLIENT_THREAD;
            case BOTH -> throw new IllegalArgumentException("BOTH does not identify a single logical thread");
        };
    }

    public static void setClientMainThread(Executor executor) {
        CLIENT_MAIN_THREAD.set(new Binding(Objects.requireNonNull(executor, "executor")));
    }

    public static void setServerMainThread(Executor executor) {
        SERVER_MAIN_THREAD.set(new Binding(Objects.requireNonNull(executor, "executor")));
    }

    public static void clearServerMainThread(Executor executor) {
        Objects.requireNonNull(executor, "executor");

        Binding current;
        do {
            current = SERVER_MAIN_THREAD.get();
            if (current == null || current.executor() != executor) {
                return;
            }
        } while (!SERVER_MAIN_THREAD.compareAndSet(current, null));
    }

    private static void executeOn(
        String side,
        AtomicReference<Binding> reference,
        Runnable task
    ) {
        Objects.requireNonNull(task, "task");

        Binding binding = reference.get();
        if (binding == null) {
            throw new RejectedExecutionException(
                "The " + side + " main-thread executor is not available");
        }

        binding.executor().execute(() -> {
            if (reference.get() == binding) {
                task.run();
            }
        });
    }

    private record Binding(Executor executor) {}
}
