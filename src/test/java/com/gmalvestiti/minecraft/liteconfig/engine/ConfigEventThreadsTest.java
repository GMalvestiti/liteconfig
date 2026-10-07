package com.gmalvestiti.minecraft.liteconfig.engine;

import com.gmalvestiti.minecraft.liteconfig.api.ConfigSide;
import com.gmalvestiti.minecraft.liteconfig.support.ConfigRegistryIsolation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayDeque;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(ConfigRegistryIsolation.class)
class ConfigEventThreadsTest {

    @Test
    void testClearDoesNotRemoveAReplacementServerBinding() {
        ArrayDeque<Runnable> oldServer = new ArrayDeque<>();
        ArrayDeque<Runnable> replacementServer = new ArrayDeque<>();
        Executor oldBinding = oldServer::addLast;
        Executor replacementBinding = replacementServer::addLast;
        AtomicInteger executions = new AtomicInteger();

        ConfigEventThreads.setServerMainThread(oldBinding);
        ConfigEventThreads.setServerMainThread(replacementBinding);
        ConfigEventThreads.clearServerMainThread(oldBinding);
        ConfigEventThreads.logicalThread(ConfigSide.SERVER).execute(executions::incrementAndGet);

        assertEquals(0, executions.get());
        assertEquals(0, oldServer.size());
        replacementServer.removeFirst().run();
        assertEquals(1, executions.get());
    }

    @Test
    void testDropsCallbacksQueuedForAStaleServerLifecycle() {
        ArrayDeque<Runnable> oldServer = new ArrayDeque<>();
        AtomicInteger executions = new AtomicInteger();

        ConfigEventThreads.setServerMainThread(oldServer::addLast);
        ConfigEventThreads.logicalThread(ConfigSide.SERVER).execute(executions::incrementAndGet);
        ConfigEventThreads.setServerMainThread(Runnable::run);
        oldServer.removeFirst().run();

        assertEquals(0, executions.get());
    }

    @Test
    void testDropsCallbacksQueuedBeforeServerBindingIsCleared() {
        ArrayDeque<Runnable> server = new ArrayDeque<>();
        Executor binding = server::addLast;
        AtomicInteger executions = new AtomicInteger();
        ConfigEventThreads.setServerMainThread(binding);
        ConfigEventThreads.logicalThread(ConfigSide.SERVER).execute(executions::incrementAndGet);

        ConfigEventThreads.clearServerMainThread(binding);
        server.removeFirst().run();

        assertEquals(0, executions.get());
    }

    @Test
    void testRebindingTheSameExecutorDoesNotReviveOldCallbacks() {
        ArrayDeque<Runnable> server = new ArrayDeque<>();
        Executor binding = server::addLast;
        AtomicInteger executions = new AtomicInteger();
        ConfigEventThreads.setServerMainThread(binding);
        ConfigEventThreads.logicalThread(ConfigSide.SERVER).execute(executions::incrementAndGet);
        ConfigEventThreads.clearServerMainThread(binding);
        ConfigEventThreads.setServerMainThread(binding);
        ConfigEventThreads.logicalThread(ConfigSide.SERVER).execute(executions::incrementAndGet);

        server.removeFirst().run();
        assertEquals(0, executions.get());
        server.removeFirst().run();
        assertEquals(1, executions.get());
    }

    @Test
    void testDropsCallbacksQueuedForAReplacedClientBinding() {
        ArrayDeque<Runnable> oldClient = new ArrayDeque<>();
        ArrayDeque<Runnable> replacement = new ArrayDeque<>();
        AtomicInteger executions = new AtomicInteger();
        ConfigEventThreads.setClientMainThread(oldClient::addLast);
        ConfigEventThreads.logicalThread(ConfigSide.CLIENT).execute(executions::incrementAndGet);
        ConfigEventThreads.setClientMainThread(replacement::addLast);
        ConfigEventThreads.logicalThread(ConfigSide.CLIENT).execute(executions::incrementAndGet);

        oldClient.removeFirst().run();
        assertEquals(0, executions.get());
        replacement.removeFirst().run();
        assertEquals(1, executions.get());
    }

    @Test
    void testRejectsCallbacksWhenTheServerBindingIsUnavailable() {
        Executor server = Runnable::run;
        ConfigEventThreads.setServerMainThread(server);
        ConfigEventThreads.clearServerMainThread(server);

        assertThrows(RejectedExecutionException.class,
            () -> ConfigEventThreads.logicalThread(ConfigSide.SERVER).execute(() -> {}));
    }
}
