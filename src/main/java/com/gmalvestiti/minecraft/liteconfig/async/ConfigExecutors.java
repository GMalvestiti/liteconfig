package com.gmalvestiti.minecraft.liteconfig.async;

import com.gmalvestiti.minecraft.liteconfig.LiteConfigCommon;

import org.slf4j.LoggerFactory;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class ConfigExecutors {

    private static final String WORKER_NAME = LiteConfigCommon.MOD_ID + "-io";
    private static final int SHUTDOWN_TIMEOUT_SECONDS = 5;
    private static final AtomicInteger WORKER_SEQUENCE = new AtomicInteger();

    private static final ExecutorService DEFAULT_EXECUTOR =
        Executors.newFixedThreadPool(
            Math.clamp(Runtime.getRuntime().availableProcessors(), 2, 4),
            ConfigExecutors::newWorker);

    private static final ConfigTaskQueue NETWORK_QUEUE = new ConfigTaskQueue(DEFAULT_EXECUTOR);

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(ConfigExecutors::drainAndStop, WORKER_NAME + "-shutdown"));
    }

    private ConfigExecutors() {}

    public static Executor defaultExecutor() {
        return NETWORK_QUEUE;
    }

    public static ConfigTaskQueue newSerialQueue() {
        return new ConfigTaskQueue(DEFAULT_EXECUTOR);
    }

    public static boolean isWorkerThread() {
        return ConfigTaskQueue.isConfigThread();
    }

    private static Thread newWorker(Runnable runnable) {
        Thread thread = new Thread(runnable, WORKER_NAME + "-" + WORKER_SEQUENCE.incrementAndGet());
        thread.setDaemon(true);
        thread.setUncaughtExceptionHandler((worker, error) ->
            LoggerFactory.getLogger(LiteConfigCommon.MOD_ID).error("Uncaught error on {}", worker.getName(), error));
        return thread;
    }

    private static void drainAndStop() {
        DEFAULT_EXECUTOR.shutdown();
        try {
            if (!DEFAULT_EXECUTOR.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                DEFAULT_EXECUTOR.shutdownNow();
            }
        } catch (InterruptedException ex) {
            DEFAULT_EXECUTOR.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
