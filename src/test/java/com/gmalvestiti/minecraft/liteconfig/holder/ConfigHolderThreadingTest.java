package com.gmalvestiti.minecraft.liteconfig.holder;

import com.gmalvestiti.minecraft.liteconfig.api.ConfigExtension;
import com.gmalvestiti.minecraft.liteconfig.api.ConfigHolder;
import com.gmalvestiti.minecraft.liteconfig.api.LiteConfig;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Config;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Entry;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Migration;
import com.gmalvestiti.minecraft.liteconfig.api.migration.ConfigData;
import com.gmalvestiti.minecraft.liteconfig.api.spi.StateCloner;
import com.gmalvestiti.minecraft.liteconfig.api.spi.Violation;
import com.gmalvestiti.minecraft.liteconfig.engine.state.ConfigState;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigError;
import com.gmalvestiti.minecraft.liteconfig.exception.LiteConfigException;
import com.gmalvestiti.minecraft.liteconfig.support.ConfigRegistryIsolation;
import com.gmalvestiti.minecraft.liteconfig.support.TestFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(ConfigRegistryIsolation.class)
class ConfigHolderThreadingTest {

    // NeoForge's ClassLoadingGuardian forbids isolated copies of mod classes.
    //? if fabric {
    @Test
    void earlyLoadingDoesNotResolveClientClassesOrMinecraftServices(@TempDir Path directory)
        throws Exception {
        ClassLoader isolated = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("net.minecraft.client.") || name.equals("net.minecraft.server.MinecraftServer")) {
                    throw new AssertionError("Early loading resolved a game service: " + name);
                }
                if (!name.startsWith("com.gmalvestiti.minecraft.liteconfig.")) {
                    return super.loadClass(name, resolve);
                }
                synchronized (getClassLoadingLock(name)) {
                    Class<?> type = findLoadedClass(name);
                    if (type == null) {
                        String resource = name.replace('.', '/') + ".class";
                        try (var bytes = LiteConfig.class.getResourceAsStream("/" + resource)) {
                            if (bytes == null) {
                                throw new ClassNotFoundException(name);
                            }
                            byte[] content = bytes.readAllBytes();
                            type = defineClass(name, content, 0, content.length);
                        } catch (IOException failure) {
                            throw new ClassNotFoundException(name, failure);
                        }
                    }
                    if (resolve) {
                        resolveClass(type);
                    }
                    return type;
                }
            }
        };
        Class<?> api = isolated.loadClass(LiteConfig.class.getName());
        Class<?> holderApi = isolated.loadClass(ConfigHolder.class.getName());
        Class<?> config = isolated.loadClass(EarlyConfig.class.getName());
        Object builder = api.getMethod("holder", Class.class).invoke(null, config);
        builder.getClass().getMethod("modId", String.class).invoke(builder, "early");
        builder.getClass().getMethod("baseDir", Path.class).invoke(builder, directory);
        Object holder = builder.getClass().getMethod("create").invoke(builder);
        try {
            holderApi.getMethod("load").invoke(holder);
            Object snapshot = holderApi.getMethod("data").invoke(holder);
            assertEquals(1, config.getField("value").getInt(snapshot));
        } finally {
            holderApi.getMethod("close").invoke(holder);
        }
    }
    //?}

    @Test
    void earlyCreateLoadAndReadNeedNoRuntimeAndDoNotBindTheCreator(@TempDir Path directory)
        throws Exception {
        ConfigRegistryIsolation.beforeGameStartup();
        ProbeConfig.userCalls.set(0);
        ProbeConfig.migrations.set(0);
        Path file = directory.resolve("mod").resolve("thread-probe.json5");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"configVersion\":1,\"value\":2}");
        AtomicReference<ConfigHolder<ProbeConfig>> created = new AtomicReference<>();

        onWorker(() -> {
            ConfigHolder<ProbeConfig> holder = probe(directory);
            created.set(holder);
            assertEquals(7, holder.data().value);
        });

        try (ConfigHolder<ProbeConfig> holder = created.get()) {
            assertEquals(1, ProbeConfig.migrations.get());
            assertTrue(ProbeConfig.userCalls.get() > 0);
            Files.writeString(file, "{\"configVersion\":2,\"value\":9}");
            holder.load();
            onWorker(() -> {
                assertEquals(9, holder.data().value);
                holder.updateAndSave(config -> config.value = 11);
                holder.load();
                holder.save();
            });
            assertEquals(11, holder.data().value);
        }
    }

    @Test
    void arbitraryCallersCreateAndOperateSynchronously(@TempDir Path directory) throws Exception {
        AtomicReference<ConfigHolder<ProbeConfig>> created = new AtomicReference<>();
        onWorker(() -> created.set(probe(directory)));
        try (ConfigHolder<ProbeConfig> holder = created.get()) {
            List<Integer> seen = new ArrayList<>();
            AtomicReference<Thread> caller = new AtomicReference<>();
            holder.onUpdate(config -> {
                assertSame(caller.get(), Thread.currentThread());
                assertSame(config, holder.data());
                seen.add(config.value);
            });
            holder.onSave(config -> assertSame(caller.get(), Thread.currentThread()));
            holder.onLoad(config -> assertSame(caller.get(), Thread.currentThread()));

            onWorker(() -> {
                caller.set(Thread.currentThread());
                assertTrue(holder.updateAndSave(config -> config.value = 4).accepted());
                assertEquals(List.of(4), seen, "events complete before the operation returns");
                holder.load();
                holder.save();
            });
            caller.set(Thread.currentThread());
            holder.update(config -> config.value = 5);
            assertEquals(List.of(4, 5), seen);
        }
    }

    @Test
    void holderEventsAndEntryListenersRunOutsideTheMonitorAndOnTheCallingThread(@TempDir Path directory) {
        ListenerProbe.fieldListenerHeldMonitor.set(false);
        try (ConfigHolder<ListenerProbe> holder = LiteConfig.holder(ListenerProbe.class)
            .modId("mod").baseDir(directory).create()) {
            Object monitor = operationLock(holder);
            ListenerProbe.operationMonitor.set(monitor);
            AtomicBoolean holderEventHeldMonitor = new AtomicBoolean();
            AtomicReference<Thread> caller = new AtomicReference<>(Thread.currentThread());
            List<String> events = new ArrayList<>();
            holder.onUpdate(config -> {
                assertSame(caller.get(), Thread.currentThread());
                holderEventHeldMonitor.compareAndSet(false, Thread.holdsLock(monitor));
                events.add("update");
            });
            holder.onLoad(config -> {
                assertSame(caller.get(), Thread.currentThread());
                holderEventHeldMonitor.compareAndSet(false, Thread.holdsLock(monitor));
                events.add("load");
            });
            holder.onSave(config -> {
                assertSame(caller.get(), Thread.currentThread());
                holderEventHeldMonitor.compareAndSet(false, Thread.holdsLock(monitor));
                events.add("save");
            });

            holder.update(config -> config.value = 2);
            holder.load();
            holder.save();

            assertEquals(List.of("update", "load", "save"), events);
            assertFalse(holderEventHeldMonitor.get());
            assertFalse(ListenerProbe.fieldListenerHeldMonitor.get());
        } finally {
            ListenerProbe.operationMonitor.set(null);
            ListenerProbe.fieldListenerHeldMonitor.set(false);
        }
    }

    @Test
    void readsCopiesMetadataAndPendingDoNotWaitForAnInProgressOperation(@TempDir Path directory)
        throws Exception {
        try (ConfigHolder<ProbeConfig> holder = probe(directory)) {
            holder.update(config -> {
                config.value = 10;
                try {
                    onWorker(() -> {
                        assertEquals(1, holder.data().value);
                        ProbeConfig copy = holder.copy();
                        copy.value = 99;
                        assertEquals(1, holder.data().value);
                        assertNotNull(holder.metadata());
                        assertTrue(holder.pending().isEmpty());
                        assertTrue(holder.isActive());
                        assertTrue(holder.path().isPresent());
                    });
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                }
            });
            assertEquals(10, holder.data().value);
        }
    }

    @Test
    void readOnlyPolicyWorksFromAnyCallerAndStillAllowsLocalSave(@TempDir Path directory)
        throws Exception {
        try (ConfigHolder<TestFixtures.ReadOnlyConfigWithExtension> holder =
                 LiteConfig.holder(TestFixtures.ReadOnlyConfigWithExtension.class)
                     .modId("mod").baseDir(directory).create()) {
            onWorker(() -> {
                holder.load();
                holder.save();
                assertFalse(holder.update(config -> fail("mutator must not run")).accepted());
                assertFalse(holder.updateAndSave(config -> fail("mutator must not run")).accepted());
            });
            assertEquals(1, holder.data().value);
        }
    }

    @Test
    void sharedRegistrationSerializesBothGameThreadsAndRejectsNestedWork(@TempDir Path directory)
        throws Exception {
        try (ConfigHolder<ProbeConfig> clientHolder = probe(directory);
             ConfigHolder<ProbeConfig> serverHolder = probe(directory)) {
            AtomicInteger entered = new AtomicInteger();
            CountDownLatch attempting = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread server = checkedThread(() -> {
                attempting.countDown();
                serverHolder.update(config -> {
                    entered.incrementAndGet();
                    config.value++;
                });
            }, failure);

            clientHolder.update(config -> {
                config.value++;
                server.start();
                await(attempting);
                awaitBlocked(server);
                assertEquals(ConfigError.NESTED_CONFIG_OPERATION,
                    assertThrows(LiteConfigException.class, serverHolder::save).error());
                assertEquals(1, serverHolder.copy().value);
                assertNotNull(serverHolder.metadata());
                assertEquals(0, entered.get());
                assertEquals(1, clientHolder.data().value);
            });
            finish(server, failure);
            assertEquals(1, entered.get());
            assertEquals(3, clientHolder.data().value, "neither caller's update may be lost");
        }
    }

    @Test
    void closingAContendedHolderFromAnotherHoldersHookDoesNotDeadlock(@TempDir Path directory)
        throws Exception {
        try (ConfigHolder<ProbeConfig> clientHolder = probe(directory);
             ConfigHolder<ProbeConfig> serverHolder = probe(directory)) {
            CountDownLatch attempting = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread server = checkedThread(() -> {
                attempting.countDown();
                assertEquals(ConfigError.HOLDER_CLOSED, assertThrows(LiteConfigException.class,
                    () -> serverHolder.update(config -> fail("closed while waiting"))).error());
            }, failure);
            assertTrue(clientHolder.update(config -> {
                server.start();
                await(attempting);
                awaitBlocked(server);
                serverHolder.close();
                config.value = 8;
            }).accepted());
            ProbeConfig published = clientHolder.data();
            finish(server, failure);
            assertSame(published, clientHolder.data());
            assertSame(published, serverHolder.data());
        }
    }

    @Test
    void subscriptionRemovalSharesTheRegistrationMonitor(@TempDir Path directory) throws Exception {
        try (ConfigHolder<ProbeConfig> holder = probe(directory)) {
            AtomicInteger deliveries = new AtomicInteger();
            var subscription = holder.onUpdate(config -> deliveries.incrementAndGet());
            CountDownLatch attempting = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread unsubscribe = checkedThread(() -> {
                attempting.countDown();
                subscription.close();
            }, failure);
            holder.update(config -> {
                unsubscribe.start();
                await(attempting);
                awaitBlocked(unsubscribe);
                config.value = 2;
            });
            finish(unsubscribe, failure);
            int delivered = deliveries.get();
            assertTrue(delivered <= 1);
            holder.update(config -> config.value = 3);
            assertEquals(delivered, deliveries.get());
        }
    }

    @Test
    void closingAHolderAlsoRemovesASubscriptionWaitingToUnsubscribe(@TempDir Path directory)
        throws Exception {
        try (ConfigHolder<ProbeConfig> first = probe(directory);
             ConfigHolder<ProbeConfig> other = probe(directory)) {
            AtomicInteger deliveries = new AtomicInteger();
            var subscription = first.onUpdate(config -> deliveries.incrementAndGet());
            CountDownLatch attempting = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread unsubscribe = checkedThread(() -> {
                attempting.countDown();
                subscription.close();
            }, failure);
            first.update(config -> {
                unsubscribe.start();
                await(attempting);
                awaitBlocked(unsubscribe);
                first.close();
                config.value = 2;
            });
            finish(unsubscribe, failure);
            assertEquals(ConfigError.HOLDER_CLOSED, assertThrows(LiteConfigException.class,
                () -> other.update(config -> config.value = 3)).error());
            assertEquals(0, deliveries.get());
        }
    }

    @Test
    void copiesMetadataSubscriptionsAndCloseRemainAvailableFromAnyCaller(@TempDir Path directory)
        throws Exception {
        ConfigHolder<ProbeConfig> holder = probe(directory);
        ProbeConfig published = holder.data();
        onWorker(() -> {
            assertSame(published, holder.data());
            ProbeConfig copy = holder.copy();
            copy.value = 90;
            assertEquals(1, holder.data().value);
            assertNotNull(holder.metadata());
            holder.onUpdate(config -> {}).close();
            holder.close();
            holder.close();
            assertSame(com.gmalvestiti.minecraft.liteconfig.api.ConfigSubscription.NONE,
                holder.onUpdate(config -> fail("closed event")));
            assertSame(published, holder.data());
        });
        assertSame(published, holder.data());
        assertEquals(ConfigError.HOLDER_CLOSED,
            assertThrows(LiteConfigException.class, holder::save).error());
    }

    private static ConfigHolder<ProbeConfig> probe(Path directory) {
        return LiteConfig.holder(ProbeConfig.class).modId("mod").baseDir(directory).create();
    }

    private static Object operationLock(ConfigHolder<?> holder) {
        try {
            Field field = holder.getClass().getDeclaredField("state");
            field.setAccessible(true);
            return ((ConfigState<?>) field.get(holder)).operationLock();
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void onWorker(Runnable action) throws InterruptedException {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = checkedThread(action, failure);
        thread.start();
        finish(thread, failure);
    }

    private static Thread checkedThread(Runnable action, AtomicReference<Throwable> failure) {
        return new Thread(() -> {
            try {
                action.run();
            } catch (Throwable raised) {
                failure.set(raised);
            }
        });
    }

    private static void finish(Thread thread, AtomicReference<Throwable> failure) throws InterruptedException {
        thread.join(5000);
        assertFalse(thread.isAlive());
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    private static void awaitBlocked(Thread thread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (thread.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertEquals(Thread.State.BLOCKED, thread.getState());
    }

    @Config(name = "early")
    public static class EarlyConfig {
        public int value = 1;
    }

    @Config(name = "thread-probe", version = 2, stateCloner = ProbeCloner.class)
    public static class ProbeConfig implements ConfigExtension {
        static final AtomicInteger userCalls = new AtomicInteger();
        static final AtomicInteger migrations = new AtomicInteger();
        public int value = 1;

        public ProbeConfig() {
            userCalls.incrementAndGet();
        }

        @Override
        public void afterLoad() {
            userCalls.incrementAndGet();
        }

        @Override
        public void beforeSave() {
            userCalls.incrementAndGet();
        }

        @Override
        public void validate(List<Violation> violations) {
            userCalls.incrementAndGet();
        }

        @Migration(from = 1)
        static void migrate(ConfigData data) {
            migrations.incrementAndGet();
            data.set("value", 7);
        }
    }

    public static class ProbeCloner implements StateCloner<ProbeConfig> {
        public ProbeCloner() {
            ProbeConfig.userCalls.incrementAndGet();
        }

        @Override
        public ProbeConfig copy(ProbeConfig source) {
            ProbeConfig.userCalls.incrementAndGet();
            ProbeConfig copy = new ProbeConfig();
            copy.value = source.value;
            return copy;
        }
    }

    @Config(name = "entry-listener-probe")
    public static class ListenerProbe {
        static final AtomicReference<Object> operationMonitor = new AtomicReference<>();
        static final AtomicBoolean fieldListenerHeldMonitor = new AtomicBoolean();

        @Entry(listener = "changed")
        public int value = 1;

        private void changed(Integer before, Integer after, boolean fromSync) {
            Object monitor = operationMonitor.get();
            if (monitor != null && Thread.holdsLock(monitor)) {
                fieldListenerHeldMonitor.set(true);
            }
        }
    }
}
