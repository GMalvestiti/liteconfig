package com.gmalvestiti.minecraft.liteconfig.async;

import com.gmalvestiti.minecraft.liteconfig.api.ConfigSide;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConfigEventExecutorsTest {

    @Test
    void testClearDoesNotRemoveAReplacementServerExecutor() {
        Executor oldServer = Runnable::run;
        Executor replacement = Runnable::run;
        AtomicInteger executions = new AtomicInteger();

        ConfigEventExecutors.setServerMainThread(oldServer);
        ConfigEventExecutors.setServerMainThread(replacement);
        ConfigEventExecutors.clearServerMainThread(oldServer);
        ConfigEventExecutors.logicalThread(ConfigSide.SERVER).execute(executions::incrementAndGet);

        assertEquals(1, executions.get());
    }

    @Test
    void testDropsCallbacksQueuedForAStaleServerLifecycle() {
        ArrayDeque<Runnable> oldServer = new ArrayDeque<>();
        AtomicInteger executions = new AtomicInteger();

        ConfigEventExecutors.setServerMainThread(oldServer::addLast);
        ConfigEventExecutors.logicalThread(ConfigSide.SERVER).execute(executions::incrementAndGet);
        ConfigEventExecutors.setServerMainThread(Runnable::run);
        oldServer.removeFirst().run();

        assertEquals(0, executions.get());
    }

    @Test
    void testRejectsCallbacksWhenTheServerExecutorIsUnavailable() {
        Executor server = Runnable::run;
        ConfigEventExecutors.setServerMainThread(server);
        ConfigEventExecutors.clearServerMainThread(server);

        assertThrows(
                RejectedExecutionException.class,
                () -> ConfigEventExecutors.logicalThread(ConfigSide.SERVER).execute(() -> {
                }));
    }
}
