package com.gmalvestiti.minecraft.liteconfig.registry;

import com.gmalvestiti.minecraft.liteconfig.api.ConfigExtension;
import com.gmalvestiti.minecraft.liteconfig.api.ConfigHolder;
import com.gmalvestiti.minecraft.liteconfig.api.ConfigSubscription;
import com.gmalvestiti.minecraft.liteconfig.api.ConfigType;
import com.gmalvestiti.minecraft.liteconfig.api.FailurePolicy;
import com.gmalvestiti.minecraft.liteconfig.api.SyncMode;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Config;
import com.gmalvestiti.minecraft.liteconfig.context.ConfigSettings;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigScope;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigError;
import com.gmalvestiti.minecraft.liteconfig.exception.LiteConfigException;
import com.gmalvestiti.minecraft.liteconfig.support.ConfigRegistryIsolation;
import com.gmalvestiti.minecraft.liteconfig.support.TestFixtures;
import com.gmalvestiti.minecraft.liteconfig.support.TestSyncPlatform;
import com.gmalvestiti.minecraft.liteconfig.storage.ConfigFileOwnership;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(ConfigRegistryIsolation.class)
class ConfigRegistryConcurrencyTest {
    @Test
    void syncRegistrationDoesNotHoldRegistryLock(@TempDir Path directory) throws Exception {
        CountDownLatch manifestRefreshEntered = new CountDownLatch(1);
        CountDownLatch finishManifestRefresh = new CountDownLatch(1);
        CountDownLatch unrelatedFinished = new CountDownLatch(1);
        AtomicReference<RegisteredConfig<RegistrationManifestConfig>> blocking = new AtomicReference<>();
        AtomicReference<RegisteredConfig<TestFixtures.SimpleConfig>> unrelated = new AtomicReference<>();
        AtomicReference<Throwable> registrationFailure = new AtomicReference<>();
        AtomicReference<Throwable> unrelatedFailure = new AtomicReference<>();

        TestSyncPlatform.setManifestScheduler(() -> {
            if (Thread.currentThread().getName().equals("registry-blocking-manifest-refresh")) {
                manifestRefreshEntered.countDown();
                await(finishManifestRefresh);
            }
        });

        Thread registering = start("registry-blocking-manifest-refresh", registrationFailure,
            () -> blocking.set(ConfigRegistry.register(settings(RegistrationManifestConfig.class, directory))));
        Thread unrelatedRegistration = null;
        try {
            await(manifestRefreshEntered);
            unrelatedRegistration = start("registry-unrelated-registration", unrelatedFailure, () -> {
                unrelated.set(ConfigRegistry.register(settings(TestFixtures.SimpleConfig.class, directory)));
                unrelatedFinished.countDown();
            });

            assertTrue(unrelatedFinished.await(2, TimeUnit.SECONDS),
                "an unrelated registration must not wait for a manifest refresh");
            assertNull(unrelatedFailure.get());
        } finally {
            finishManifestRefresh.countDown();
            join(registering);
            join(unrelatedRegistration);
            ConfigRegistry.release(blocking.get());
            ConfigRegistry.release(unrelated.get());
        }

        assertNull(registrationFailure.get());
    }

    @Test
    void syncReleaseDoesNotHoldRegistryLock(@TempDir Path directory) throws Exception {
        CountDownLatch manifestRefreshEntered = new CountDownLatch(1);
        CountDownLatch finishManifestRefresh = new CountDownLatch(1);
        CountDownLatch unrelatedFinished = new CountDownLatch(1);
        AtomicReference<Throwable> releaseFailure = new AtomicReference<>();
        AtomicReference<Throwable> unrelatedFailure = new AtomicReference<>();
        AtomicReference<RegisteredConfig<ReleaseManifestConfig>> blocking =
            new AtomicReference<>(ConfigRegistry.register(settings(ReleaseManifestConfig.class, directory)));
        AtomicReference<RegisteredConfig<TestFixtures.SimpleConfig>> unrelated =
            new AtomicReference<>(ConfigRegistry.register(settings(TestFixtures.SimpleConfig.class, directory)));

        TestSyncPlatform.setManifestScheduler(() -> {
            if (Thread.currentThread().getName().equals("registry-blocking-release-manifest-refresh")) {
                manifestRefreshEntered.countDown();
                await(finishManifestRefresh);
            }
        });

        Thread releasing = start("registry-blocking-release-manifest-refresh", releaseFailure,
            () -> ConfigRegistry.release(blocking.get()));
        Thread unrelatedRelease = null;
        try {
            await(manifestRefreshEntered);
            unrelatedRelease = start("registry-unrelated-release", unrelatedFailure, () -> {
                ConfigRegistry.release(unrelated.get());
                unrelatedFinished.countDown();
            });

            assertTrue(unrelatedFinished.await(2, TimeUnit.SECONDS),
                "an unrelated release must not wait for a manifest refresh");
            assertNull(unrelatedFailure.get());
        } finally {
            finishManifestRefresh.countDown();
            join(releasing);
            join(unrelatedRelease);
            ConfigRegistry.release(blocking.get());
            ConfigRegistry.release(unrelated.get());
        }

        assertNull(releaseFailure.get());
    }

    @Test
    void interruptedWaiterStillSharesTheResultAndRetainsItsInterruptFlag(@TempDir Path directory)
        throws Exception {
        BlockingConfig.reset();
        AtomicReference<RegisteredConfig<BlockingConfig>> first = new AtomicReference<>();
        AtomicReference<RegisteredConfig<BlockingConfig>> second = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread creator = start("registry-creator", failure,
            () -> first.set(ConfigRegistry.register(settings(BlockingConfig.class, directory))));
        Thread waiter = null;
        try {
            await(BlockingConfig.loading);
            waiter = start("registry-interrupted-waiter", failure, () -> {
                Thread.currentThread().interrupt();
                second.set(ConfigRegistry.register(settings(BlockingConfig.class, directory)));
                interrupted.set(Thread.currentThread().isInterrupted());
            });
            awaitWaiting(waiter);
            BlockingConfig.finish.countDown();
            join(creator);
            join(waiter);

            assertNull(failure.get());
            assertTrue(interrupted.get());
            assertSame(first.get(), second.get());
            assertEquals(1, BlockingConfig.loads.get());
        } finally {
            BlockingConfig.finish.countDown();
            join(creator);
            join(waiter);
            ConfigRegistry.release(first.get());
            ConfigRegistry.release(second.get());
        }
        assertEmptyRegistry();
    }

    @Test
    void failedCreationReleasesAllWaitersAndAllowsAnotherAttempt(@TempDir Path directory) throws Exception {
        BlockingConfig.reset();
        BlockingConfig.failure = new IllegalStateException("Load failed");
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        AtomicReference<Throwable> secondFailure = new AtomicReference<>();
        Thread creator = start("registry-failing-creator", firstFailure,
            () -> ConfigRegistry.register(settings(BlockingConfig.class, directory)));
        Thread waiter = null;
        try {
            await(BlockingConfig.loading);
            waiter = start("registry-failing-waiter", secondFailure,
                () -> ConfigRegistry.register(settings(BlockingConfig.class, directory)));
            awaitWaiting(waiter);
            BlockingConfig.finish.countDown();
            join(creator);
            join(waiter);

            assertNotNull(firstFailure.get());
            assertSame(firstFailure.get(), secondFailure.get());
            assertEmptyRegistry();

            BlockingConfig.failure = null;
            RegisteredConfig<BlockingConfig> recovered =
                ConfigRegistry.register(settings(BlockingConfig.class, directory));
            assertEquals(2, BlockingConfig.loads.get());
            ConfigRegistry.release(recovered);
            assertEmptyRegistry();
        } finally {
            BlockingConfig.finish.countDown();
            join(creator);
            join(waiter);
        }
    }

    @Test
    void failedCreationClosesRetainedAccessAndSubscriptionsAndAllowsRetry(@TempDir Path directory)
        throws Exception {
        ConfigFileOwnership ownership = new ConfigFileOwnership();
        AtomicReference<RegisteredConfig<?>> retained = new AtomicReference<>();
        AtomicReference<ConfigHolder<?>> access = new AtomicReference<>();
        AtomicReference<ConfigSubscription> subscription = new AtomicReference<>();
        AtomicReference<Object> published = new AtomicReference<>();
        IllegalStateException primary = new IllegalStateException("First acquisition failed");

        assertSame(primary, assertThrows(IllegalStateException.class, () -> RegisteredConfig.create(
            settings(TestFixtures.SimpleConfig.class, directory), ownership, registration -> {
                retained.set(registration);
                ConfigHolder<?> holder = registration.access();
                access.set(holder);
                published.set(holder.data());
                subscription.set(holder.onUpdate(config -> fail("failed registration event")));
                throw primary;
            })));

        assertSame(access.get(), retained.get().access());
        assertEquals(ConfigError.HOLDER_CLOSED,
            assertThrows(LiteConfigException.class, access.get()::copy).error());
        assertEquals(ConfigError.HOLDER_CLOSED,
            assertThrows(LiteConfigException.class, () -> access.get().update(config -> {})).error());
        assertSame(ConfigSubscription.NONE, access.get().onUpdate(config -> {}));
        assertSame(published.get(), access.get().data());
        assertNotNull(instanceField(access.get(), "state"), "state");
        assertNull(instanceField(access.get(), "registration"), "registration");
        for (String name : List.of("notifier", "listeners", "listener")) {
            assertNull(instanceField(subscription.get(), name), name);
        }
        subscription.get().close();
        access.get().close();
        assertEmptyRegistry();
        assertTrue(((Map<?, ?>) instanceField(ownership, "owners")).isEmpty(),
            "failed creation must release its file claim");

        RegisteredConfig<TestFixtures.SimpleConfig> retry =
            RegisteredConfig.create(settings(TestFixtures.SimpleConfig.class, directory), ownership, ignored -> {});
        try {
            assertNotSame(retained.get(), retry);
            assertNotSame(access.get(), retry.access());
            assertTrue(retry.access().update(config -> config.value = 7).accepted());
            assertEquals(7, retry.access().data().value);
        } finally {
            ConfigRegistry.release(retry);
        }
        assertEmptyRegistry();
    }

    @Test
    void failedAcquisitionDoesNotRunUnrelatedFailingCleanup(@TempDir Path directory) throws Exception {
        RegisteredConfig<UnscopedSyncedConfig> first =
            ConfigRegistry.register(settings(UnscopedSyncedConfig.class, directory));
        IllegalStateException cleanup = new IllegalStateException("Cleanup failed");
        TestSyncPlatform.setManifestScheduler(() -> {
            throw cleanup;
        });

        LiteConfigException primary = assertThrows(LiteConfigException.class,
            () -> ConfigRegistry.register(new ConfigSettings<>(
                UnscopedSyncedConfig.class, new ConfigScope("other"), directory)));

        assertEquals(ConfigError.CONFLICTING_CONFIG_SCOPE, primary.error());
        assertEquals(0, primary.getSuppressed().length);
        assertFalse(first.isClosed());
        assertSame(first, ConfigRegistry.register(settings(UnscopedSyncedConfig.class, directory)));
        TestSyncPlatform.setSyncRecipientsAvailable(false);
        assertTrue(first.access().update(state -> state.value = 4).accepted());
        assertSame(cleanup, assertThrows(IllegalStateException.class, () -> ConfigRegistry.release(first)));
        assertEmptyRegistry();
    }

    @Test
    void nestedCreationRejectsWaitingForAnUnfinishedConfig(@TempDir Path directory) throws Exception {
        BlockingConfig.reset();
        NestedCreationConfig.directory = directory;
        AtomicReference<RegisteredConfig<BlockingConfig>> pending = new AtomicReference<>();
        AtomicReference<Throwable> creationFailure = new AtomicReference<>();
        AtomicReference<Throwable> nestedFailure = new AtomicReference<>();
        Thread creator = start("registry-pending-creator", creationFailure,
            () -> pending.set(ConfigRegistry.register(settings(BlockingConfig.class, directory))));
        Thread nestedCreator = null;
        try {
            await(BlockingConfig.loading);
            nestedCreator = start("registry-nested-creator", nestedFailure,
                () -> ConfigRegistry.register(settings(NestedCreationConfig.class, directory)));
            join(nestedCreator);

            assertInstanceOf(LiteConfigException.class, nestedFailure.get());
            assertEquals(ConfigError.REENTRANT_CONFIG_REGISTRATION,
                ((LiteConfigException) nestedFailure.get()).error());
            assertNull(pending.get(), "nested creation must fail before the other config finishes");

            BlockingConfig.finish.countDown();
            join(creator);
            assertNull(creationFailure.get());
            assertNotNull(pending.get());
            RegisteredConfig<NestedCreationConfig> retry =
                ConfigRegistry.register(settings(NestedCreationConfig.class, directory));
            ConfigRegistry.release(retry);
        } finally {
            BlockingConfig.finish.countDown();
            join(creator);
            join(nestedCreator);
            ConfigRegistry.release(pending.get());
            NestedCreationConfig.directory = null;
        }
        assertEmptyRegistry();
    }

    @Test
    void registrationPublishesTheLatestWorldAfterSyncRegistrationReturns(@TempDir Path directory)
        throws Exception {
        Path firstWorld = directory.resolve("first");
        Path secondWorld = directory.resolve("second");
        writeWorld(firstWorld, 11);
        writeWorld(secondWorld, 22);
        ConfigRegistry.activateWorld(firstWorld);
        CountDownLatch syncEntered = new CountDownLatch(1);
        CountDownLatch finishSync = new CountDownLatch(1);
        AtomicReference<RegisteredConfig<SwitchingWorldConfig>> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        TestSyncPlatform.setManifestScheduler(() -> {
            if (Thread.currentThread().getName().equals("registry-world-creator")) {
                syncEntered.countDown();
                await(finishSync);
            }
        });
        Thread creator = start("registry-world-creator", failure, () -> result.set(
            ConfigRegistry.register(settings(SwitchingWorldConfig.class, directory.resolve("config")))));
        try {
            await(syncEntered);
            ConfigRegistry.activateWorld(secondWorld);
            finishSync.countDown();
            join(creator);

            assertNull(failure.get());
            assertEquals(22, result.get().state().published().value);
            assertEquals(worldFile(secondWorld), result.get().pathResolver().activePath().orElseThrow());
        } finally {
            finishSync.countDown();
            join(creator);
            ConfigRegistry.release(result.get());
            ConfigRegistry.deactivateWorld();
        }
    }

    @Test
    void failingOldWorldTransitionDoesNotDeactivateANewerWorld(@TempDir Path directory) throws Exception {
        Path firstWorld = directory.resolve("first");
        Path secondWorld = directory.resolve("second");
        writeWorld(firstWorld, 11);
        writeWorld(secondWorld, 22);
        SwitchingWorldConfig.loading = new CountDownLatch(1);
        SwitchingWorldConfig.finish = new CountDownLatch(1);
        RegisteredConfig<SwitchingWorldConfig> config =
            ConfigRegistry.register(settings(SwitchingWorldConfig.class, directory.resolve("config")));
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        AtomicReference<Throwable> secondFailure = new AtomicReference<>();
        Thread first = start("registry-first-world", firstFailure, () -> ConfigRegistry.activateWorld(firstWorld));
        Thread second = null;
        try {
            await(SwitchingWorldConfig.loading);
            second = start("registry-second-world", secondFailure,
                () -> ConfigRegistry.activateWorld(secondWorld));
            awaitCondition(() -> secondWorld.equals(activeWorldRoot()));
            SwitchingWorldConfig.finish.countDown();
            join(first);
            join(second);

            assertNotNull(firstFailure.get());
            assertNull(secondFailure.get());
            assertEquals(secondWorld, activeWorldRoot());
            assertTrue(config.isActive());
            assertEquals(worldFile(secondWorld), config.pathResolver().activePath().orElseThrow());
            assertEquals(22, config.state().published().value);
        } finally {
            SwitchingWorldConfig.finish.countDown();
            join(first);
            join(second);
            SwitchingWorldConfig.loading = null;
            ConfigRegistry.release(config);
            ConfigRegistry.deactivateWorld();
        }
    }

    private static <T> ConfigSettings<T> settings(Class<T> type, Path directory) {
        return new ConfigSettings<>(type, new ConfigScope("mod"), directory);
    }

    private static Thread start(String name, AtomicReference<Throwable> failure, Runnable work) {
        Thread thread = new Thread(() -> {
            try {
                work.run();
            } catch (Throwable caught) {
                failure.compareAndSet(null, caught);
            }
        }, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void join(Thread thread) throws InterruptedException {
        if (thread != null) {
            thread.join(5000);
            assertFalse(thread.isAlive(), thread.getName());
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "Coordination timed out");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    private static void awaitWaiting(Thread thread) throws InterruptedException {
        awaitCondition(() -> thread.getState() == Thread.State.WAITING);
    }

    private static void awaitCondition(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertTrue(condition.getAsBoolean(), "Coordination timed out");
    }

    private static void assertEmptyRegistry() throws Exception {
        assertTrue(((Map<?, ?>) field("REGISTRATIONS").get(null)).isEmpty());
    }

    private static Path activeWorldRoot() {
        try {
            return (Path) field("activeWorldRoot").get(null);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static Field field(String name) throws NoSuchFieldException {
        Field field = ConfigRegistry.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Object instanceField(Object target, String name) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static Path worldFile(Path root) {
        return root.resolve("liteconfig").resolve("mod").resolve("switching.json5");
    }

    private static void writeWorld(Path root, int value) throws Exception {
        Path file = worldFile(root);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"value\":" + value + "}");
    }

    @Config(name = "blocking-registration")
    public static class BlockingConfig implements ConfigExtension {
        static final AtomicInteger loads = new AtomicInteger();
        static CountDownLatch loading;
        static CountDownLatch finish;
        static RuntimeException failure;
        public int value = 1;

        static void reset() {
            loads.set(0);
            loading = new CountDownLatch(1);
            finish = new CountDownLatch(1);
            failure = null;
        }

        @Override
        public void afterLoad() {
            loads.incrementAndGet();
            loading.countDown();
            await(finish);
            if (failure != null) {
                throw failure;
            }
        }
    }

    @Config(name = "switching", type = ConfigType.WORLD, sync = SyncMode.ENABLED)
    public static class SwitchingWorldConfig implements ConfigExtension {
        static CountDownLatch loading;
        static CountDownLatch finish;
        public int value = 5;

        @Override
        public void afterLoad() {
            if (value == 11 && loading != null) {
                loading.countDown();
                await(finish);
                throw new IllegalStateException("Old world failed to load");
            }
        }
    }

    @Config(name = "registration-event-lock", sync = SyncMode.ENABLED)
    public static class RegistrationManifestConfig {
        public int value = 1;
    }

    @Config(name = "release-event-lock", sync = SyncMode.ENABLED)
    public static class ReleaseManifestConfig {
        public int value = 1;
    }

    @Config(name = "nested-creation", readFailurePolicy = FailurePolicy.STRICT)
    public static class NestedCreationConfig implements ConfigExtension {
        static Path directory;
        public int value = 1;

        @Override
        public void afterLoad() {
            ConfigRegistry.register(settings(BlockingConfig.class, directory));
        }
    }

    @Config(name = "unscoped-registration", modIdDirectory = false, sync = SyncMode.ENABLED)
    public static class UnscopedSyncedConfig {
        public int value = 1;
    }

}
