package com.gmalvestiti.minecraft.liteconfig.holder;

import com.gmalvestiti.minecraft.liteconfig.api.ConfigHolder;
import com.gmalvestiti.minecraft.liteconfig.api.ConfigExtension;
import com.gmalvestiti.minecraft.liteconfig.api.LiteConfig;
import com.gmalvestiti.minecraft.liteconfig.api.UpdateResult;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Config;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Entry;
import com.gmalvestiti.minecraft.liteconfig.api.spi.StateCloner;
import com.gmalvestiti.minecraft.liteconfig.api.spi.Violation;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigError;
import com.gmalvestiti.minecraft.liteconfig.exception.LiteConfigException;
import com.gmalvestiti.minecraft.liteconfig.support.ConfigRegistryIsolation;
import com.gmalvestiti.minecraft.liteconfig.support.TestFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(ConfigRegistryIsolation.class)
class ConfigHolderSynchronousTest {

    @Test
    void testCloningValidationHooksAndFieldCallbacksStayOnTheCallingGameThread(@TempDir Path tempDir) {
        ThreadBoundConfig.gameThread = Thread.currentThread();
        ThreadBoundConfig.events.clear();
        try (ConfigHolder<ThreadBoundConfig> holder =
            LiteConfig.holder(ThreadBoundConfig.class).modId("mod").baseDir(tempDir).create()) {
            assertTrue(ThreadBoundConfig.events.contains("validate"));
            assertTrue(ThreadBoundConfig.events.contains("before-save"));
            assertTrue(ThreadBoundConfig.events.contains("copy"));
            ThreadBoundConfig.events.clear();

            holder.updateAndSave(config -> {
                assertSame(ThreadBoundConfig.gameThread, Thread.currentThread());
                ThreadBoundConfig.events.add("mutate");
                config.value = 2;
            });
            assertTrue(ThreadBoundConfig.events.indexOf("mutate")
                < ThreadBoundConfig.events.indexOf("validate"));
            assertTrue(ThreadBoundConfig.events.indexOf("validate")
                < ThreadBoundConfig.events.indexOf("before-save"));
            assertTrue(ThreadBoundConfig.events.indexOf("before-save")
                < ThreadBoundConfig.events.indexOf("callback"));
            ThreadBoundConfig.events.clear();

            holder.load();
            assertTrue(ThreadBoundConfig.events.contains("after-load"));
            assertTrue(ThreadBoundConfig.events.contains("validate"));
            holder.save();
            assertTrue(ThreadBoundConfig.events.contains("before-save"));
        } finally {
            ThreadBoundConfig.gameThread = null;
            ThreadBoundConfig.events.clear();
        }
    }

    @Test
    void testLoadsSavesAndUpdatesBeforeReturningOnTheCallingGameThread(@TempDir Path tempDir)
        throws Exception {
        ConfigHolder<TestFixtures.ConfigWithExtension> holder = holder(tempDir);
        Thread gameThread = Thread.currentThread();

        assertTrue(holder.updateAndSave(config -> {
            assertSame(gameThread, Thread.currentThread());
            config.value = 7;
        }).accepted());
        assertEquals(7, holder.data().value);
        assertTrue(Files.readString(tempDir.resolve("with-extension.json5")).contains("\"value\": 7"));

        holder.update(config -> {
            assertSame(gameThread, Thread.currentThread());
            config.value = 42;
        });
        assertEquals(42, holder.data().value);
        holder.load();
        assertEquals(7, holder.data().value);
        holder.update(config -> config.value = 9);
        holder.save();
        assertTrue(Files.readString(tempDir.resolve("with-extension.json5")).contains("\"value\": 9"));
    }

    @Test
    void testRejectsNestedOperationsFromHooksAndFieldCallbacks(@TempDir Path tempDir) {
        ThreadBoundConfig.gameThread = Thread.currentThread();
        ThreadBoundConfig.events.clear();
        AtomicInteger rejected = new AtomicInteger();
        try (ConfigHolder<ThreadBoundConfig> holder =
            LiteConfig.holder(ThreadBoundConfig.class).modId("mod").baseDir(tempDir).create()) {
            ThreadBoundConfig.nestedOperation = () -> {
                LiteConfigException failure = assertThrows(LiteConfigException.class, holder::save);
                assertEquals(ConfigError.NESTED_CONFIG_OPERATION, failure.error());
                rejected.incrementAndGet();
            };

            assertTrue(holder.update(config -> config.value = 2).accepted());
            assertEquals(1, rejected.get(), "field callbacks must reject nested config operations");
            holder.save();
            assertEquals(2, rejected.get(), "beforeSave must reject nested config operations");
            holder.load();
            assertEquals(3, rejected.get(), "afterLoad must reject nested config operations");
        } finally {
            ThreadBoundConfig.nestedOperation = null;
            ThreadBoundConfig.gameThread = null;
            ThreadBoundConfig.events.clear();
        }
    }

    @Test
    void testDistinctRegistrationsCanMutateInOneSynchronousCallingThreadFlow(@TempDir Path tempDir) {
        ConfigHolder<TestFixtures.ConfigWithExtension> first = holder(tempDir.resolve("first"));
        ConfigHolder<TestFixtures.SimpleConfig> second =
            LiteConfig.holder(TestFixtures.SimpleConfig.class).modId("mod")
                .baseDir(tempDir.resolve("second")).create();
        Thread gameThread = Thread.currentThread();

        assertTrue(first.update(config -> {
            assertSame(gameThread, Thread.currentThread());
            assertTrue(second.updateAndSave(other -> {
                assertSame(gameThread, Thread.currentThread());
                other.value = 9;
            }).accepted());
            assertEquals(9, second.data().value);
            config.value = 4;
        }).accepted());

        assertEquals(4, first.data().value);
        assertEquals(9, second.data().value);
    }

    @Test
    void testClosingInsideSaveHookDefersReferencesUntilSaveCompletes(@TempDir Path tempDir)
        throws Exception {
        closeDuringHook(tempDir, true);
    }

    @Test
    void testClosingInsideLoadHookDefersReferencesUntilLoadCompletes(@TempDir Path tempDir)
        throws Exception {
        closeDuringHook(tempDir, false);
    }

    private static void closeDuringHook(Path tempDir, boolean saving) throws Exception {
        ThreadBoundConfig.gameThread = Thread.currentThread();
        ThreadBoundConfig.events.clear();
        AtomicInteger hookCalls = new AtomicInteger();
        try (ConfigHolder<ThreadBoundConfig> holder =
            LiteConfig.holder(ThreadBoundConfig.class).modId("mod").baseDir(tempDir).create()) {
            holder.update(config -> config.value = 7);
            ThreadBoundConfig.nestedOperation = () -> {
                ThreadBoundConfig.nestedOperation = null;
                hookCalls.incrementAndGet();
                holder.close();
                holder.close();
                assertEquals(7, holder.data().value,
                    "the in-progress operation still owns its state until it returns");
                assertEquals(ConfigError.HOLDER_CLOSED,
                    assertThrows(LiteConfigException.class, holder::metadata).error());
            };

            if (saving) {
                holder.save();
                assertTrue(Files.readString(tempDir.resolve("thread-bound.json5"))
                    .contains("\"value\": 7"));
            } else {
                holder.load();
            }

            assertEquals(1, hookCalls.get());
            assertThrows(NullPointerException.class, holder::data);
            holder.close();
        } finally {
            ThreadBoundConfig.nestedOperation = null;
            ThreadBoundConfig.gameThread = null;
            ThreadBoundConfig.events.clear();
        }
    }

    @Test
    void testKeepsPublishedStateIsolatedFromCallerMutation(@TempDir Path tempDir) {
        ConfigHolder<TestFixtures.ConfigWithExtension> holder = holder(tempDir);
        TestFixtures.ConfigWithExtension copy = holder.copy();
        copy.value = 99;

        assertNotSame(holder.data(), copy);
        assertEquals(1, holder.data().value);
    }

    @Test
    void testRejectsUpdatesThatFailValidationWithoutPublishing(@TempDir Path tempDir) {
        ConfigHolder<TestFixtures.StrictUpdateConfigWithExtension> holder =
            LiteConfig.holder(TestFixtures.StrictUpdateConfigWithExtension.class)
                .modId("mod").baseDir(tempDir).create();

        LiteConfigException failure = assertThrows(LiteConfigException.class,
            () -> holder.update(config -> config.value = -1));

        assertEquals(ConfigError.VALIDATION_FAILED, failure.error());
        assertEquals(1, holder.data().value);
    }

    @Test
    void testReportsRejectionsAsAValueUnderFallbackPolicy(@TempDir Path tempDir) {
        ConfigHolder<TestFixtures.ConfigWithExtension> holder = holder(tempDir);

        UpdateResult result = holder.updateAndSave(config -> config.value = -1);

        assertInstanceOf(UpdateResult.Rejected.class, result);
        assertFalse(result.accepted());
        assertEquals(List.of("nonNegative"), result.violations().stream().map(v -> v.id()).toList());
        assertEquals(1, holder.data().value);
    }

    @Test
    void testFallsBackAndBacksUpMalformedDataOnLoad(@TempDir Path tempDir) throws Exception {
        ConfigHolder<TestFixtures.ConfigWithExtension> holder = holder(tempDir);
        Path file = tempDir.resolve("with-extension.json5");
        Files.writeString(file, "{");

        holder.load();

        assertEquals(1, holder.data().value);
        assertFalse(Files.exists(file));
        try (var entries = Files.list(tempDir)) {
            assertEquals(1, entries.filter(p -> p.getFileName().toString().contains(".corrupt-")).count());
        }
    }

    @Test
    void testThrowsOnMalformedDataUnderStrictReadPolicy(@TempDir Path tempDir) throws Exception {
        ConfigHolder<TestFixtures.StrictConfigWithExtension> holder =
            LiteConfig.holder(TestFixtures.StrictConfigWithExtension.class)
                .modId("mod").baseDir(tempDir).create();
        Path file = tempDir.resolve("strict-with-extension.json5");
        Files.writeString(file, "{");

        LiteConfigException failure = assertThrows(LiteConfigException.class, holder::load);

        assertEquals(ConfigError.MALFORMED_CONFIG_DATA, failure.error());
        assertTrue(Files.exists(file));
        try (var entries = Files.list(tempDir)) {
            assertEquals(0, entries.filter(p -> p.getFileName().toString().contains(".corrupt-")).count());
        }
    }

    @Test
    void testRethrowsAnErrorOnTheCallingThreadUnchangedAndRecovers(@TempDir Path tempDir) {
        ConfigHolder<TestFixtures.ConfigWithExtension> holder = holder(tempDir);
        Error raised = new Error("mutator exploded");

        Error thrown = assertThrows(Error.class, () -> holder.update(config -> {
            config.value = 4;
            throw raised;
        }));

        assertSame(raised, thrown);
        assertEquals(1, holder.data().value);
        assertTrue(holder.update(config -> config.value = 8).accepted());
        assertEquals(8, holder.data().value);
    }

    @Test
    void testCrossThreadReadsDoNotBlockAnUpdateOrObserveTheUnpublishedCandidate(@TempDir Path tempDir)
        throws Exception {
        ConfigHolder<TestFixtures.ConfigWithExtension> holder = holder(tempDir);
        CountDownLatch candidateChanged = new CountDownLatch(1);
        CountDownLatch readFinished = new CountDownLatch(1);
        AtomicReference<Throwable> readerFailure = new AtomicReference<>();
        AtomicInteger observed = new AtomicInteger();
        Thread reader = new Thread(() -> {
            try {
                assertTrue(candidateChanged.await(5, TimeUnit.SECONDS));
                observed.set(holder.data().value);
            } catch (Throwable failure) {
                readerFailure.set(failure);
            } finally {
                readFinished.countDown();
            }
        }, "config-snapshot-reader");
        reader.start();
        try {
            holder.update(config -> {
                config.value = 17;
                candidateChanged.countDown();
                try {
                    assertTrue(readFinished.await(5, TimeUnit.SECONDS),
                        "data() must not acquire the game-thread mutation lock");
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                }
                assertEquals(1, observed.get());
            });
        } finally {
            candidateChanged.countDown();
            reader.join(5000);
        }

        assertFalse(reader.isAlive());
        if (readerFailure.get() != null) {
            throw new AssertionError(readerFailure.get());
        }
        assertEquals(17, holder.data().value);
    }

    @Test
    void testPublishesWholeSnapshotsToReadersWhileMutatingOnlyOnTheGameThread(@TempDir Path tempDir)
        throws Exception {
        ConfigHolder<TestFixtures.SimpleConfig> holder =
            LiteConfig.holder(TestFixtures.SimpleConfig.class).modId("mod").baseDir(tempDir).create();
        holder.update(config -> {
            config.value = 0;
            config.text = "0";
        });
        AtomicBoolean finished = new AtomicBoolean();
        AtomicReference<Throwable> readerFailure = new AtomicReference<>();
        CountDownLatch reading = new CountDownLatch(1);
        AtomicInteger finalObserved = new AtomicInteger(-1);
        Thread reader = new Thread(() -> {
            try {
                reading.countDown();
                do {
                    TestFixtures.SimpleConfig snapshot = holder.data();
                    assertEquals(Integer.toString(snapshot.value), snapshot.text);
                } while (!finished.get());
                finalObserved.set(holder.data().value);
            } catch (Throwable failure) {
                readerFailure.set(failure);
            }
        }, "config-snapshot-reader");
        reader.start();
        try {
            assertTrue(reading.await(5, TimeUnit.SECONDS));
            for (int value = 1; value <= 1000; value++) {
                int next = value;
                holder.update(config -> {
                    config.value = next;
                    config.text = Integer.toString(next);
                });
            }
        } finally {
            finished.set(true);
            reader.join(5000);
        }

        assertFalse(reader.isAlive());
        if (readerFailure.get() != null) {
            throw new AssertionError(readerFailure.get());
        }
        assertEquals(1000, finalObserved.get());
        assertEquals(1000, holder.data().value);
    }

    private static ConfigHolder<TestFixtures.ConfigWithExtension> holder(Path tempDir) {
        return LiteConfig.holder(TestFixtures.ConfigWithExtension.class)
            .modId("mod").baseDir(tempDir).create();
    }

    @Config(name = "thread-bound", stateCloner = ThreadBoundCloner.class)
    public static class ThreadBoundConfig implements ConfigExtension {
        static Thread gameThread;
        static Runnable nestedOperation;
        static final List<String> events = new ArrayList<>();

        @Entry(callback = "changed")
        public int value = 1;

        @Override
        public void afterLoad() {
            record("after-load");
            runNestedOperation();
        }

        @Override
        public void beforeSave() {
            record("before-save");
            runNestedOperation();
        }

        @Override
        public void validate(List<Violation> violations) {
            record("validate");
        }

        private void changed(Integer before, Integer after, boolean fromSync) {
            record("callback");
            runNestedOperation();
        }

        private static void runNestedOperation() {
            if (nestedOperation != null) {
                nestedOperation.run();
            }
        }

        private static void record(String event) {
            assertSame(gameThread, Thread.currentThread());
            events.add(event);
        }
    }

    public static class ThreadBoundCloner implements StateCloner<ThreadBoundConfig> {
        @Override
        public ThreadBoundConfig copy(ThreadBoundConfig source) {
            ThreadBoundConfig.record("copy");
            ThreadBoundConfig copy = new ThreadBoundConfig();
            copy.value = source.value;
            return copy;
        }
    }
}
