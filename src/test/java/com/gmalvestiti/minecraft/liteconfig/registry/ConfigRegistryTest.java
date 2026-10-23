package com.gmalvestiti.minecraft.liteconfig.registry;

import com.gmalvestiti.minecraft.liteconfig.api.ConfigHolder;
import com.gmalvestiti.minecraft.liteconfig.api.ConfigExtension;
import com.gmalvestiti.minecraft.liteconfig.api.FailurePolicy;
import com.gmalvestiti.minecraft.liteconfig.api.LiteConfig;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Config;
import com.gmalvestiti.minecraft.liteconfig.context.ConfigSettings;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigError;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigScope;
import com.gmalvestiti.minecraft.liteconfig.exception.LiteConfigException;
import com.gmalvestiti.minecraft.liteconfig.support.TestFixtures;
import com.gmalvestiti.minecraft.liteconfig.support.ConfigRegistryIsolation;
import com.gmalvestiti.minecraft.liteconfig.support.TestSyncPlatform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotSame;

@ExtendWith(ConfigRegistryIsolation.class)
class ConfigRegistryTest {

    @AfterEach
    void resetInitialization() {
        InitializingConfig.onLoad = () -> {};
    }

    @Test
    void testSameConfigClassKeepsIndependentStateAcrossDirectories(@TempDir Path tempDir) {
        Path other = tempDir.resolve("other");

        ConfigHolder<TestFixtures.SimpleConfig> here = holder(tempDir);
        ConfigHolder<TestFixtures.SimpleConfig> there = holder(other);

        assertNotSame(here, there);
        here.update(config -> config.value = 42);

        assertEquals(42, here.data().value);
        assertEquals(1, there.data().value);
    }

    @Test
    void testRegistersAConfigOnlyOnce(@TempDir Path tempDir) {
        ConfigHolder<TestFixtures.SimpleConfig> first = holder(tempDir);
        ConfigHolder<TestFixtures.SimpleConfig> second = holder(tempDir);

        assertSame(first, second);
        first.update(config -> config.value = 42);
        assertEquals(42, second.data().value);
    }

    @Test
    void testIndependentModNamespacesHaveIndependentHolders(@TempDir Path tempDir) {
        try (ConfigHolder<TestFixtures.SimpleConfig> first =
                 LiteConfig.holder(TestFixtures.SimpleConfig.class)
                     .modId("first").baseDir(tempDir).create();
             ConfigHolder<TestFixtures.SimpleConfig> second =
                 LiteConfig.holder(TestFixtures.SimpleConfig.class)
                     .modId("second").baseDir(tempDir).create()) {
            assertNotSame(first, second);
            first.update(state -> state.value = 7);
            assertEquals(1, second.data().value);
        }
    }

    @Config(name = "concurrent-registration")
    public static class ConcurrentRegistrationConfig implements ConfigExtension {
        static final AtomicInteger loads = new AtomicInteger();
        static CountDownLatch loading;
        static CountDownLatch finishLoading;
        public int value = 1;

        @Override
        public void afterLoad() {
            loads.incrementAndGet();
            loading.countDown();
            try {
                if (!finishLoading.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("Registration did not finish");
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError(failure);
            }
        }
    }

    @Test
    void testConcurrentCreationSharesOneRegistrationClosedByEitherHolder(@TempDir Path tempDir) throws Exception {
        ConcurrentRegistrationConfig.loads.set(0);
        ConcurrentRegistrationConfig.loading = new CountDownLatch(1);
        ConcurrentRegistrationConfig.finishLoading = new CountDownLatch(1);
        AtomicReference<ConfigHolder<ConcurrentRegistrationConfig>> first = new AtomicReference<>();
        AtomicReference<ConfigHolder<ConcurrentRegistrationConfig>> second = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch secondStarted = new CountDownLatch(1);
        Thread firstThread = new Thread(() -> {
            try {
                first.set(LiteConfig.holder(ConcurrentRegistrationConfig.class)
                    .modId("mod").baseDir(tempDir).create());
            } catch (Throwable caught) {
                failure.compareAndSet(null, caught);
            }
        }, "first-registration");
        Thread secondThread = new Thread(() -> {
            secondStarted.countDown();
            try {
                second.set(LiteConfig.holder(ConcurrentRegistrationConfig.class)
                    .modId("mod").baseDir(tempDir).create());
            } catch (Throwable caught) {
                failure.compareAndSet(null, caught);
            }
        }, "second-registration");
        firstThread.setDaemon(true);
        secondThread.setDaemon(true);
        try {
            firstThread.start();
            assertTrue(ConcurrentRegistrationConfig.loading.await(5, TimeUnit.SECONDS));
            secondThread.start();
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
            ConcurrentRegistrationConfig.finishLoading.countDown();
            firstThread.join(5000);
            secondThread.join(5000);
            assertTrue(!firstThread.isAlive() && !secondThread.isAlive());
            assertNull(failure.get());
            assertEquals(1, ConcurrentRegistrationConfig.loads.get());
            assertSame(first.get(), second.get());
            assertSame(first.get().data(), second.get().data());

            first.get().close();
            assertEquals(ConfigError.HOLDER_CLOSED,
                assertThrows(LiteConfigException.class, second.get()::copy).error());
            second.get().close();
            assertTrue(((Map<?, ?>) field(ConfigRegistry.class, null, "REGISTRATIONS")).isEmpty());
        } finally {
            ConcurrentRegistrationConfig.finishLoading.countDown();
            firstThread.interrupt();
            secondThread.interrupt();
            firstThread.join(5000);
            secondThread.join(5000);
            if (first.get() != null) {
                first.get().close();
            }
            if (second.get() != null) {
                second.get().close();
            }
        }
    }

    @Test
    void testClosingEitherHolderReleasesTheRegistration(@TempDir Path tempDir) {
        ConfigHolder<TestFixtures.SimpleConfig> first = holder(tempDir);
        ConfigHolder<TestFixtures.SimpleConfig> second = holder(tempDir);
        first.update(config -> config.value = 42);

        first.close();
        assertEquals(ConfigError.HOLDER_CLOSED,
            assertThrows(LiteConfigException.class, second::copy).error());
        second.close();

        ConfigHolder<TestFixtures.SimpleConfig> reopened = holder(tempDir);
        assertNotSame(first, reopened);
        assertNotSame(second, reopened);
        assertEquals(1, reopened.data().value);
        second.close();
        assertTrue(reopened.update(state -> state.value = 7).accepted(),
            "an old closed handle must not release a replacement registration");
        reopened.close();
    }

    @Test
    void testSharedCloseClearsRegistriesButPreservesSavedFile(@TempDir Path tempDir) throws Exception {
        ConfigHolder<TestFixtures.SimpleConfig> first = holder(tempDir);
        ConfigHolder<TestFixtures.SimpleConfig> second = holder(tempDir);
        first.updateAndSave(state -> state.value = 17);

        first.close();
        assertEquals(ConfigError.HOLDER_CLOSED,
            assertThrows(LiteConfigException.class, second::copy).error());
        second.close();
        second.close();

        assertTrue(((Map<?, ?>) field(ConfigRegistry.class, null, "REGISTRATIONS")).isEmpty());
        Object ownership = field(ConfigRegistry.class, null, "OWNERSHIP");
        assertTrue(((Map<?, ?>) field(ownership.getClass(), ownership, "owners")).isEmpty());
        assertTrue(Files.exists(tempDir.resolve("mod").resolve("simple.json5")));
        try (ConfigHolder<TestFixtures.SimpleConfig> reopened = holder(tempDir)) {
            assertEquals(17, reopened.data().value);
        }
    }

    @Test
    void testReleaseFailureStillDropsHolderAndFileOwnership(@TempDir Path tempDir) throws Exception {
        RuntimeException raised = new IllegalStateException("manifest refresh failed");
        ConfigHolder<TestFixtures.SyncedConfig> holder = LiteConfig.holder(TestFixtures.SyncedConfig.class)
            .modId("mod").baseDir(tempDir).create();
        TestFixtures.SyncedConfig published = holder.data();
        TestSyncPlatform.setManifestScheduler(() -> { throw raised; });

        assertSame(raised, assertThrows(IllegalStateException.class, holder::close));
        assertTrue(((Map<?, ?>) field(ConfigRegistry.class, null, "REGISTRATIONS")).isEmpty());
        assertEquals(ConfigError.HOLDER_CLOSED,
            assertThrows(LiteConfigException.class, holder::metadata).error());
        assertSame(published, holder.data());

        TestSyncPlatform.setManifestScheduler(() -> {});
        try (ConfigHolder<ReplacementConfig> replacement = LiteConfig.holder(ReplacementConfig.class)
            .modId("mod").baseDir(tempDir).create()) {
            assertEquals(1, replacement.data().value);
        }
    }

    @Test
    void testFinalCloseReleasesImmediatelyOnTheCallingGameThread(@TempDir Path tempDir) throws Exception {
        Thread gameThread = Thread.currentThread();
        AtomicInteger releases = new AtomicInteger();
        ConfigHolder<TestFixtures.SyncedConfig> holder = LiteConfig.holder(TestFixtures.SyncedConfig.class)
            .modId("mod").baseDir(tempDir).create();
        TestSyncPlatform.setManifestScheduler(() -> {
            assertSame(gameThread, Thread.currentThread());
            releases.incrementAndGet();
        });
        TestFixtures.SyncedConfig published = holder.data();

        holder.close();

        assertEquals(1, releases.get());
        assertTrue(((Map<?, ?>) field(ConfigRegistry.class, null, "REGISTRATIONS")).isEmpty());
        Object ownership = field(ConfigRegistry.class, null, "OWNERSHIP");
        assertTrue(((Map<?, ?>) field(ownership.getClass(), ownership, "owners")).isEmpty());
        assertSame(published, holder.data());
    }

    private static Object field(Class<?> type, Object target, String name) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    @Config(name = "simple")
    public static class ReplacementConfig {
        public int value = 1;
    }

    @Test
    void testRejectsASecondScopeForTheSameRegistration(@TempDir Path tempDir) {
        ConfigRegistry.register(settings(UnscopedConfig.class, "first", tempDir));

        LiteConfigException failure = assertThrows(
            LiteConfigException.class,
            () -> ConfigRegistry.register(
                settings(UnscopedConfig.class, "second", tempDir))
        );

        assertEquals(ConfigError.CONFLICTING_CONFIG_SCOPE, failure.error());
    }

    @Config(name = "unscoped", modIdDirectory = false)
    static class UnscopedConfig {
        public int value = 1;
    }

    @Test
    void testScopedRegistrationsAllowOneClassToBelongToDifferentMods(@TempDir Path tempDir) {
        RegisteredConfig<TestFixtures.SimpleConfig> first =
            ConfigRegistry.register(settings(TestFixtures.SimpleConfig.class, "first", tempDir));
        RegisteredConfig<TestFixtures.SimpleConfig> second =
            ConfigRegistry.register(settings(TestFixtures.SimpleConfig.class, "second", tempDir));

        TestFixtures.SimpleConfig changed = new TestFixtures.SimpleConfig();
        changed.value = 7;
        first.state().writing(() -> first.state().replace(changed));
        assertEquals(7, first.state().published().value);
        assertEquals(1, second.state().published().value);
        assertTrue(Files.exists(tempDir.resolve("first").resolve("simple.json5")));
        assertTrue(Files.exists(tempDir.resolve("second").resolve("simple.json5")));
    }

    @Test
    void testLoadHookCanRegisterAnotherConfig(@TempDir Path tempDir) {
        InitializingConfig.onLoad = () -> assertNotNull(ConfigRegistry.register(
            settings(TestFixtures.SimpleConfig.class, "mod", tempDir)));
        RegisteredConfig<InitializingConfig> registration =
            ConfigRegistry.register(settings(InitializingConfig.class, "mod", tempDir));

        assertNotNull(registration);
    }

    @Test
    void testRejectsRecursiveRegistrationOfTheSameConfig(@TempDir Path tempDir) {
        InitializingConfig.onLoad = () -> ConfigRegistry.register(
            settings(InitializingConfig.class, "mod", tempDir));
        LiteConfigException failure = assertThrows(
            LiteConfigException.class,
            () -> ConfigRegistry.register(settings(InitializingConfig.class, "mod", tempDir))
        );

        assertEquals(ConfigError.REENTRANT_CONFIG_REGISTRATION, failure.error());
    }

    @Test
    void testLoadHookCanAcquireAnInitializedConfig(@TempDir Path tempDir) {
        RegisteredConfig<TestFixtures.SimpleConfig> existing =
            ConfigRegistry.register(settings(TestFixtures.SimpleConfig.class, "mod", tempDir));

        InitializingConfig.onLoad = () -> assertSame(existing,
            ConfigRegistry.register(settings(TestFixtures.SimpleConfig.class, "mod", tempDir)));
        RegisteredConfig<InitializingConfig> created =
            ConfigRegistry.register(settings(InitializingConfig.class, "mod", tempDir));

        ConfigRegistry.release(created);
        ConfigRegistry.release(existing);
    }

    private static ConfigHolder<TestFixtures.SimpleConfig> holder(Path baseDirectory) {
        return LiteConfig.holder(TestFixtures.SimpleConfig.class)
            .modId("mod")
            .baseDir(baseDirectory)
            .create();
    }

    private static <T> ConfigSettings<T> settings(
        Class<T> type,
        String modId,
        Path baseDirectory
    ) {
        return new ConfigSettings<>(type, new ConfigScope(modId), baseDirectory);
    }

    @Config(name = "initializing", readFailurePolicy = FailurePolicy.STRICT)
    public static class InitializingConfig implements ConfigExtension {
        static Runnable onLoad = () -> {};
        public int value = 1;

        @Override
        public void afterLoad() {
            onLoad.run();
        }
    }
}
