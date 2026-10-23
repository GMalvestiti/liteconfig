package com.gmalvestiti.minecraft.liteconfig.registry;

import com.gmalvestiti.minecraft.liteconfig.api.ConfigHolder;
import com.gmalvestiti.minecraft.liteconfig.api.LiteConfig;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Config;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigError;
import com.gmalvestiti.minecraft.liteconfig.exception.LiteConfigException;
import com.gmalvestiti.minecraft.liteconfig.support.ConfigRegistryIsolation;
import com.gmalvestiti.minecraft.liteconfig.support.TestFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(ConfigRegistryIsolation.class)
class ConfigRegistryInitializationTest {
    private static Path directory;
    private static CountDownLatch initializing;
    private static CountDownLatch finishInitialization;
    private static CountDownLatch constructorsEntered;

    @Test
    void staticHolderCanInitializeItsOwnConfigBeforeRuntimeStartup(@TempDir Path tempDir) throws Exception {
        ConfigRegistryIsolation.beforeGameStartup();
        directory = tempDir;

        try (ConfigHolder<StaticFirstConfig> holder = StaticFirstConfig.HOLDER) {
            assertEquals(7, holder.data().value);
            assertSame(holder, holder(StaticFirstConfig.class));
        }
    }

    @Test
    void externalCreationSharesTheHolderCreatedByAStaticInitializer(@TempDir Path tempDir) throws Exception {
        ConfigRegistryIsolation.beforeGameStartup();
        directory = tempDir;

        try (ConfigHolder<ExternalFirstConfig> holder = holder(ExternalFirstConfig.class)) {
            assertSame(holder, ExternalFirstConfig.HOLDER);
            assertEquals(9, holder.data().value);
        }
    }

    @Test
    void failedClassInitializationDoesNotReserveARegistration(@TempDir Path tempDir) throws Exception {
        directory = tempDir;

        ExceptionInInitializerError failure = assertThrows(ExceptionInInitializerError.class,
            () -> holder(FailedConfig.class));
        assertEquals("static initialization failed", failure.getCause().getMessage());
        var registrations = ConfigRegistry.class.getDeclaredField("REGISTRATIONS");
        registrations.setAccessible(true);
        assertTrue(((Map<?, ?>) registrations.get(null)).isEmpty());
    }

    @Test
    void unannotatedTypesStillReportTheMissingConfigMarker(@TempDir Path tempDir) {
        directory = tempDir;

        LiteConfigException failure = assertThrows(LiteConfigException.class, () -> holder(int.class));
        assertEquals(ConfigError.MISSING_CONFIG_MARKER, failure.error());
    }

    @Test
    void mutuallyDependentStaticHoldersFailInsteadOfDeadlocking(@TempDir Path tempDir) throws Exception {
        ConfigRegistryIsolation.beforeGameStartup();
        directory = tempDir;
        constructorsEntered = new CountDownLatch(2);
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        AtomicReference<Throwable> secondFailure = new AtomicReference<>();
        Thread first = thread("first-static-holder", firstFailure, () -> holder(FirstDependentConfig.class));
        Thread second = thread("second-static-holder", secondFailure, () -> holder(SecondDependentConfig.class));

        first.start();
        second.start();
        first.join(5000);
        second.join(5000);

        assertFalse(first.isAlive(), "the first static holder must not deadlock");
        assertFalse(second.isAlive(), "the second static holder must not deadlock");
        assertNotNull(firstFailure.get());
        assertNotNull(secondFailure.get());
        assertTrue(isNestedCreationFailure(firstFailure.get()) || isNestedCreationFailure(secondFailure.get()));
        var registrations = ConfigRegistry.class.getDeclaredField("REGISTRATIONS");
        registrations.setAccessible(true);
        assertTrue(((Map<?, ?>) registrations.get(null)).isEmpty());
    }

    @Test
    void competingStaticInitializationDoesNotDeadlockOrHoldTheRegistryLock(@TempDir Path tempDir)
        throws Exception {
        ConfigRegistryIsolation.beforeGameStartup();
        directory = tempDir;
        initializing = new CountDownLatch(1);
        finishInitialization = new CountDownLatch(1);
        AtomicReference<ConfigHolder<ConcurrentConfig>> staticHolder = new AtomicReference<>();
        AtomicReference<ConfigHolder<ConcurrentConfig>> externalHolder = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread initializer = thread("config-static-initializer", failure,
            () -> staticHolder.set(ConcurrentConfig.HOLDER));
        Thread creator = thread("config-external-creator", failure,
            () -> externalHolder.set(holder(ConcurrentConfig.class)));

        initializer.start();
        try {
            await(initializing);
            creator.start();
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                boolean initializingClass = false;
                while (!initializingClass && creator.isAlive()) {
                    for (StackTraceElement frame : creator.getStackTrace()) {
                        if (frame.getClassName().equals(ConfigRegistry.class.getName())
                            && frame.getMethodName().equals("initializeConfigClass")) {
                            initializingClass = true;
                        }
                    }
                    if (!initializingClass) {
                        Thread.sleep(1);
                    }
                }
                assertTrue(initializingClass);
                try (ConfigHolder<TestFixtures.SimpleConfig> unrelated = holder(TestFixtures.SimpleConfig.class)) {
                    assertEquals(1, unrelated.data().value);
                }
            });
        } finally {
            finishInitialization.countDown();
            initializer.join(5000);
            creator.join(5000);
        }

        assertFalse(initializer.isAlive(), "static holder initialization must finish");
        assertFalse(creator.isAlive(), "external creation must finish");
        assertNull(failure.get());
        assertSame(staticHolder.get(), externalHolder.get());
        assertEquals(11, externalHolder.get().data().value);
        externalHolder.get().close();
    }

    private static <T> ConfigHolder<T> holder(Class<T> type) {
        return LiteConfig.holder(type).modId("init-test").baseDir(directory).create();
    }

    private static Thread thread(String name, AtomicReference<Throwable> failure, Runnable work) {
        Thread thread = new Thread(() -> {
            try {
                work.run();
            } catch (Throwable thrown) {
                failure.compareAndSet(null, thrown);
            }
        }, name);
        thread.setDaemon(true);
        return thread;
    }

    private static boolean isNestedCreationFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof LiteConfigException configFailure
                && configFailure.error() == ConfigError.REENTRANT_CONFIG_REGISTRATION) {
                return true;
            }
        }
        return false;
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "initialization coordination timed out");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    @Config(name = "static-first")
    public static class StaticFirstConfig {
        public static final ConfigHolder<StaticFirstConfig> HOLDER = holder(StaticFirstConfig.class);
        public int value = 7;
    }

    @Config(name = "external-first")
    public static class ExternalFirstConfig {
        public static final ConfigHolder<ExternalFirstConfig> HOLDER;
        static {
            HOLDER = holder(ExternalFirstConfig.class);
        }
        public int value = 9;
    }

    @Config(name = "concurrent-initialization")
    public static class ConcurrentConfig {
        public static final ConfigHolder<ConcurrentConfig> HOLDER;
        static {
            initializing.countDown();
            await(finishInitialization);
            HOLDER = holder(ConcurrentConfig.class);
        }
        public int value = 11;
    }

    @Config(name = "failed-initialization")
    public static class FailedConfig {
        private static final int INITIAL = initialValue();
        public int value = INITIAL;

        private static int initialValue() {
            throw new IllegalStateException("static initialization failed");
        }
    }

    @Config(name = "first-dependent")
    public static class FirstDependentConfig {
        public static final ConfigHolder<FirstDependentConfig> HOLDER = holder(FirstDependentConfig.class);
        public int value = 1;

        public FirstDependentConfig() {
            constructorsEntered.countDown();
            await(constructorsEntered);
            holder(SecondDependentConfig.class);
        }
    }

    @Config(name = "second-dependent")
    public static class SecondDependentConfig {
        public static final ConfigHolder<SecondDependentConfig> HOLDER = holder(SecondDependentConfig.class);
        public int value = 2;

        public SecondDependentConfig() {
            constructorsEntered.countDown();
            await(constructorsEntered);
            holder(FirstDependentConfig.class);
        }
    }
}
