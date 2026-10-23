package com.gmalvestiti.minecraft.liteconfig.holder;

import com.gmalvestiti.minecraft.liteconfig.api.ConfigHolder;
import com.gmalvestiti.minecraft.liteconfig.api.LiteConfig;
import com.gmalvestiti.minecraft.liteconfig.api.ConfigSubscription;
import com.gmalvestiti.minecraft.liteconfig.api.ConfigType;
import com.gmalvestiti.minecraft.liteconfig.api.FailurePolicy;
import com.gmalvestiti.minecraft.liteconfig.api.SyncMode;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Config;
import com.gmalvestiti.minecraft.liteconfig.api.metadata.ConfigMetadata;
import com.gmalvestiti.minecraft.liteconfig.context.ConfigSettings;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigScope;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigError;
import com.gmalvestiti.minecraft.liteconfig.exception.LiteConfigException;
import com.gmalvestiti.minecraft.liteconfig.registry.ConfigRegistry;
import com.gmalvestiti.minecraft.liteconfig.support.ConfigRegistryIsolation;
import com.gmalvestiti.minecraft.liteconfig.support.RegisteredConfigs;
import com.gmalvestiti.minecraft.liteconfig.support.TestFixtures;
import com.gmalvestiti.minecraft.liteconfig.support.TestSyncPlatform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(ConfigRegistryIsolation.class)
class ConfigHolderImplementationTest {

    @Test
    void testRegistrationReturnsItsCanonicalHolder(@TempDir Path tempDir) {
        var registration = RegisteredConfigs.create(new ConfigSettings<>(
            TestFixtures.SimpleConfig.class, new ConfigScope("mod"), tempDir));
        try (ConfigHolder<TestFixtures.SimpleConfig> holder = registration.access()) {
            assertSame(holder, registration.access());
            holder.close();
            assertSame(holder, registration.access(), "a retired registration retains its closed holder");
            assertEquals(ConfigError.HOLDER_CLOSED,
                assertThrows(LiteConfigException.class, registration.access()::copy).error());
        }
    }

    @Test
    void testHoldersShareOneMonitorForAtomicOperations(@TempDir Path tempDir) throws Exception {
        try (ConfigHolder<TestFixtures.SimpleConfig> first =
                 LiteConfig.holder(TestFixtures.SimpleConfig.class).modId("mod").baseDir(tempDir).create();
             ConfigHolder<TestFixtures.SimpleConfig> second =
                 LiteConfig.holder(TestFixtures.SimpleConfig.class).modId("mod").baseDir(tempDir).create()) {
            assertSame(first, second);
            var state = (com.gmalvestiti.minecraft.liteconfig.engine.state.ConfigState<?>)
                field(first, "state");
            Object lock = ((com.gmalvestiti.minecraft.liteconfig.engine.state.ConfigState<?>)
                field(first, "state")).operationLock();
            assertSame(lock, ((com.gmalvestiti.minecraft.liteconfig.engine.state.ConfigState<?>)
                field(second, "state")).operationLock());

            first.update(config -> {
                assertTrue(Thread.holdsLock(lock));
                assertEquals(ConfigError.NESTED_CONFIG_OPERATION,
                    assertThrows(LiteConfigException.class, second::save).error());
                second.close();
                config.value = 7;
            });

            assertEquals(7, ((TestFixtures.SimpleConfig) state.published()).value);
            assertSame(state.published(), first.data());
            assertSame(state.published(), second.data());
            assertEquals(ConfigError.HOLDER_CLOSED,
                assertThrows(LiteConfigException.class, first::copy).error());
        }
    }

    @Test
    void testLogsCompletedLoadAndSaveOperationsOnSimpleHolder(@TempDir Path tempDir) {
        ConfigScope scope = spy(new ConfigScope("mod"));
        ConfigHolder<TestFixtures.ConfigWithExtension> holder = holder(tempDir, scope);

        holder.load();
        holder.save();

        verify(scope).logInfo("Config load operation completed successfully");
        verify(scope).logInfo("Config save operation completed successfully");
    }

    @Test
    void testDoesNotLogSuccessWhenTheWritePolicySwallowsAFailedSave(@TempDir Path tempDir) throws Exception {
        Path unusableDir = tempDir.resolve("config");
        Files.createFile(unusableDir);
        ConfigScope scope = spy(new ConfigScope("mod"));
        ConfigHolder<TestFixtures.ConfigWithExtension> holder =
            holder(unusableDir, scope);

        holder.save();

        verify(scope, never()).logInfo("Config save operation completed successfully");
        verify(scope, times(2)).logError(contains("keeping in-memory state"), any(LiteConfigException.class));
    }

    @Test
    void testFailedFallbackWriteDoesNotPublishUpdateCandidate(@TempDir Path tempDir) throws Exception {
        Path unusableDir = tempDir.resolve("config");
        Files.createFile(unusableDir);
        ConfigHolder<TestFixtures.ConfigWithExtension> holder =
            holder(unusableDir, new ConfigScope("mod"));

        var result = holder.updateAndSave(config -> config.value = 9);

        assertFalse(result.accepted());
        assertEquals(1, holder.data().value);
    }

    @Test
    void testFallbackLoadRestoresDefaultsWithoutCompletionEvents(@TempDir Path tempDir) throws Exception {
        ConfigScope scope = spy(new ConfigScope("mod"));
        try (ConfigHolder<TestFixtures.ConfigWithExtension> holder = holder(tempDir, scope)) {
            holder.update(config -> config.value = 8);
            List<String> events = new ArrayList<>();
            holder.onLoad(config -> events.add("load"));
            holder.onUpdate(config -> events.add("update"));
            holder.onSave(config -> events.add("save"));
            Files.writeString(holder.path().orElseThrow(), "{");

            holder.load();

            assertEquals(1, holder.data().value);
            assertTrue(events.isEmpty());
            verify(scope, never()).logInfo("Config load operation completed successfully");
        }
    }

    @Test
    void testCreatesTheConfigFileDuringInitialization(@TempDir Path tempDir) throws Exception {
        ConfigScope scope = spy(new ConfigScope("mod"));

        holder(tempDir, scope);

        assertTrue(Files.readString(tempDir.resolve("mod").resolve("with-extension.json5")).contains("\"value\": 1"));
    }

    @Test
    void testAdoptsAnExistingValidFileDuringInitialization(@TempDir Path tempDir) throws Exception {
        Files.createDirectories(tempDir.resolve("mod"));
        Files.writeString(tempDir.resolve("mod").resolve("with-extension.json5"), "{\"value\":7}");
        ConfigScope scope = spy(new ConfigScope("mod"));

        ConfigHolder<TestFixtures.ConfigWithExtension> holder = holder(tempDir, scope);

        assertEquals(7, holder.data().value, "a valid file must seed the holder without calling load()");
        assertTrue(Files.readString(tempDir.resolve("mod").resolve("with-extension.json5")).contains("\"value\": 7"),
            "adopted values must survive the write-back");
    }

    @Test
    void testBacksUpAnInvalidFileAndPersistsDefaultsDuringInitialization(@TempDir Path tempDir) throws Exception {
        Files.createDirectories(tempDir.resolve("mod"));
        Path file = tempDir.resolve("mod").resolve("with-extension.json5");
        Files.writeString(file, "{\"value\":-5}");
        ConfigScope scope = spy(new ConfigScope("mod"));

        ConfigHolder<TestFixtures.ConfigWithExtension> holder = holder(tempDir, scope);

        assertEquals(1, holder.data().value);
        assertTrue(Files.readString(file).contains("\"value\": 1"));
        try (var entries = Files.list(tempDir.resolve("mod"))) {
            assertEquals(1, entries.filter(p -> p.getFileName().toString().contains(".corrupt-")).count());
        }
    }

    @Test
    void testPublishesTheSchemaOfTheConfigItHolds(@TempDir Path tempDir) {
        ConfigHolder<TestFixtures.ConstrainedConfig> holder =
            LiteConfig.holder(TestFixtures.ConstrainedConfig.class)
                .modId("mod")
                .baseDir(tempDir.toString())
                .create();

        ConfigMetadata metadata = holder.metadata();

        assertEquals(TestFixtures.ConstrainedConfig.class, metadata.type());
        assertEquals(1, metadata.property("hudScale").orElseThrow().constraints().min().getAsDouble());
        assertTrue(metadata.property("section.max_depth").isPresent());
    }

    @Test
    void testPersistsUpdateAndSaveBeforeNotifyingAndBeforeTheNextUpdate(@TempDir Path tempDir)
        throws Exception {
        ConfigHolder<TestFixtures.ConfigWithExtension> holder =
            holder(tempDir, new ConfigScope("mod"));
        Thread gameThread = Thread.currentThread();
        List<String> notifications = new ArrayList<>();
        holder.onUpdate(state -> {
            assertSame(gameThread, Thread.currentThread());
            assertEquals(state.value, holder.data().value);
            notifications.add("update-" + state.value);
        });
        holder.onSave(state -> {
            assertSame(gameThread, Thread.currentThread());
            notifications.add("save-" + state.value);
            try {
                assertTrue(Files.readString(tempDir.resolve("mod").resolve("with-extension.json5"))
                    .contains("\"value\": " + state.value));
            } catch (java.io.IOException failure) {
                throw new AssertionError(failure);
            }
        });

        holder.updateAndSave(state -> state.value = 2);
        assertEquals(List.of("update-2", "save-2"), notifications);
        holder.update(state -> state.value = 3);

        assertEquals(List.of("update-2", "save-2", "update-3"), notifications);
        assertEquals(3, holder.data().value);
        assertTrue(Files.readString(tempDir.resolve("mod").resolve("with-extension.json5"))
            .contains("\"value\": 2"));
    }

    @Test
    void testClosingRemovesAllSubscriptionsAndInvalidatesEveryHolder(@TempDir Path tempDir)
        throws Exception {
        try (ConfigHolder<TestFixtures.SimpleConfig> first =
                 LiteConfig.holder(TestFixtures.SimpleConfig.class).modId("mod").baseDir(tempDir).create();
             ConfigHolder<TestFixtures.SimpleConfig> second =
                 LiteConfig.holder(TestFixtures.SimpleConfig.class).modId("mod").baseDir(tempDir).create()) {
            List<String> events = new ArrayList<>();
            List<ConfigSubscription> removed = List.of(
                first.onUpdate(state -> events.add("closed-update")),
                first.onLoad(state -> events.add("closed-load")),
                first.onSave(state -> events.add("closed-save")),
                first.onUpdate(state -> events.add("closed-second-update")),
                second.onUpdate(state -> events.add("update")),
                second.onLoad(state -> events.add("load")),
                second.onSave(state -> events.add("save")));

            second.update(state -> state.value = 2);
            assertEquals(List.of("closed-update", "closed-second-update", "update"), events);
            events.clear();

            first.close();
            for (ConfigSubscription subscription : removed) {
                subscription.close();
                assertNull(field(subscription, "notifier"));
                assertNull(field(subscription, "listener"));
                assertNull(field(subscription, "listeners"));
            }

            assertEquals(ConfigError.HOLDER_CLOSED,
                assertThrows(LiteConfigException.class,
                    () -> second.update(state -> state.value = 7)).error());
            assertEquals(ConfigError.HOLDER_CLOSED,
                assertThrows(LiteConfigException.class, second::load).error());
            assertEquals(ConfigError.HOLDER_CLOSED,
                assertThrows(LiteConfigException.class, second::save).error());
            assertSame(ConfigSubscription.NONE, second.onUpdate(state -> events.add("late")));
            assertTrue(events.isEmpty());
        }
    }

    @Test
    void testClosedHolderRetainsPublishedStateAndSubscriptionDropsReferences(@TempDir Path tempDir)
        throws Exception {
        ConfigHolder<TestFixtures.ConfigWithExtension> holder =
            holder(tempDir, new ConfigScope("mod"));
        Object retainedState = field(holder, "state");
        Object published = holder.data();
        ConfigSubscription subscription = holder.onUpdate(config -> {});

        holder.close();
        holder.close();
        subscription.close();

        assertNull(field(holder, "registration"));
        assertSame(retainedState, field(holder, "state"));
        assertNull(field(subscription, "notifier"));
        assertNull(field(subscription, "listener"));
        assertNull(field(subscription, "listeners"));
        assertSame(published, holder.data());
        assertEquals(ConfigError.HOLDER_CLOSED,
            assertThrows(LiteConfigException.class, holder::metadata).error());
        assertEquals(ConfigError.HOLDER_CLOSED,
            assertThrows(LiteConfigException.class, holder::copy).error());
        assertEquals(ConfigError.HOLDER_CLOSED,
            assertThrows(LiteConfigException.class, holder::pending).error());
        assertEquals(ConfigError.HOLDER_CLOSED,
            assertThrows(LiteConfigException.class, holder::type).error());
        assertEquals(ConfigError.HOLDER_CLOSED,
            assertThrows(LiteConfigException.class, holder::isActive).error());
        assertEquals(ConfigError.HOLDER_CLOSED,
            assertThrows(LiteConfigException.class, holder::path).error());
        assertSame(ConfigSubscription.NONE, holder.onUpdate(state -> {}));
        assertSame(ConfigSubscription.NONE, holder.onLoad(state -> {}));
        assertSame(ConfigSubscription.NONE, holder.onSave(state -> {}));
        assertEquals(ConfigError.HOLDER_CLOSED,
            assertThrows(LiteConfigException.class, holder::load).error());
        assertEquals(ConfigError.HOLDER_CLOSED,
            assertThrows(LiteConfigException.class, holder::save).error());
        assertEquals(ConfigError.HOLDER_CLOSED,
            assertThrows(LiteConfigException.class, () -> holder.update(state -> {})).error());
        assertEquals(ConfigError.HOLDER_CLOSED,
            assertThrows(LiteConfigException.class, () -> holder.updateAndSave(state -> {})).error());
    }

    @Test
    void testClosingDuringAnEventSkipsLaterRegistrationEvents(@TempDir Path tempDir) {
        var registration = RegisteredConfigs.create(new ConfigSettings<>(
            TestFixtures.ConfigWithExtension.class, new ConfigScope("mod"), tempDir));
        ConfigHolder<TestFixtures.ConfigWithExtension> holder =
            registration.access();
        List<Integer> seen = new ArrayList<>();
        holder.onUpdate(state -> {
            seen.add(state.value);
            holder.close();
        });
        holder.onUpdate(state -> {
            throw new AssertionError("An event removed by close must not run");
        });

        assertTrue(holder.update(state -> state.value = 2).accepted());

        assertEquals(List.of(2), seen);
        assertEquals(2, registration.state().published().value);
        assertSame(registration.state().published(), holder.data());
    }

    @Test
    void testClosingInsideTheMutatorFinishesAcceptedWorkThenRetainsPublishedState(@TempDir Path tempDir)
        throws Exception {
        var registration = RegisteredConfigs.create(new ConfigSettings<>(
            TestFixtures.ConfigWithExtension.class, new ConfigScope("mod"), tempDir));
        ConfigHolder<TestFixtures.ConfigWithExtension> closingHolder = registration.access();

        assertTrue(closingHolder.update(state -> {
            state.value = 8;
            closingHolder.close();
            assertEquals(1, closingHolder.data().value,
                "close during an operation must retain the current state until it finishes");
            assertEquals(ConfigError.HOLDER_CLOSED,
                assertThrows(LiteConfigException.class, closingHolder::metadata).error());
        }).accepted());

        assertNull(field(closingHolder, "registration"));
        assertSame(registration.state(), field(closingHolder, "state"));
        assertEquals(8, registration.state().published().value);
        assertSame(registration.state().published(), closingHolder.data());
    }

    @Test
    void testClosingDuringExternalStateWriteDefersReleaseAndSuppressesAllEvents(@TempDir Path tempDir)
        throws Exception {
        var registration = RegisteredConfigs.create(new ConfigSettings<>(
            TestFixtures.ConfigWithExtension.class, new ConfigScope("mod"), tempDir));
        ConfigHolder<TestFixtures.ConfigWithExtension> first =
            registration.access();
        ConfigHolder<TestFixtures.ConfigWithExtension> second =
            registration.access();
        List<Integer> events = new ArrayList<>();
        ConfigSubscription token = second.onUpdate(config -> events.add(config.value));
        var state = registration.state();

        registration.writing(() -> {
            first.close();
            assertEquals(1, second.data().value, "an external write retains resources until finally");
            assertEquals(ConfigError.HOLDER_CLOSED,
                assertThrows(LiteConfigException.class, second::copy).error());
            var remote = new TestFixtures.ConfigWithExtension();
            remote.value = 9;
            state.replaceRemote(remote, null, null);
            registration.holderEvents().notifyUpdated(state.published());
            return null;
        });

        assertEquals(9, state.published().value, "accepted external writes finish after close");
        assertTrue(events.isEmpty());
        assertNull(field(first, "registration"));
        assertSame(state, field(second, "state"));
        assertNull(field(token, "listener"));
        assertNull(field(token, "notifier"));
        assertNull(field(token, "listeners"));
        assertSame(state.published(), second.data());
        assertEquals(ConfigError.HOLDER_CLOSED, assertThrows(LiteConfigException.class,
            () -> registration.writing(state::clearRemote)).error());
    }

    @Test
    void testRemoteEventCanCloseAnotherAliasDuringDirectStateWrite(@TempDir Path tempDir)
        throws Exception {
        var registration = RegisteredConfigs.create(new ConfigSettings<>(
            TestFixtures.ConfigWithExtension.class, new ConfigScope("mod"), tempDir));
        ConfigHolder<TestFixtures.ConfigWithExtension> first =
            registration.access();
        ConfigHolder<TestFixtures.ConfigWithExtension> second =
            registration.access();
        var state = registration.state();
        List<Integer> events = new ArrayList<>();
        first.onUpdate(remote -> {
            events.add(remote.value);
            second.close();
            assertSame(remote, first.data(), "the writing scope retains the published snapshot");
            assertEquals(ConfigError.HOLDER_CLOSED,
                assertThrows(LiteConfigException.class, first::metadata).error());
        });
        ConfigSubscription later = second.onUpdate(remote -> events.add(-remote.value));

        registration.writing(() -> {
            var remote = new TestFixtures.ConfigWithExtension();
            remote.value = 9;
            state.replaceRemote(remote, null, null);
            registration.holderEvents().notifyUpdated(state.published());
            assertEquals(9, second.data().value);
            return null;
        });

        assertEquals(List.of(9), events);
        assertEquals(9, state.published().value);
        assertNull(field(first, "registration"));
        assertSame(state, field(second, "state"));
        assertNull(field(later, "listener"));
        assertSame(state.published(), first.data());
        assertSame(state.published(), second.data());
    }

    @Test
    void testDeferredCleanupDoesNotReplaceAnOperationError(@TempDir Path tempDir) throws Exception {
        ConfigHolder<StrictOwnershipConfig> holder =
            LiteConfig.holder(StrictOwnershipConfig.class).modId("mod").baseDir(tempDir).create();
        Object state = field(holder, "state");
        Error primary = new AssertionError("Mutation failed");
        IllegalStateException cleanup = new IllegalStateException("Cleanup failed");
        TestSyncPlatform.setManifestScheduler(() -> {
            throw cleanup;
        });

        assertSame(primary, assertThrows(AssertionError.class, () -> holder.update(config -> {
            holder.close();
            throw primary;
        })));

        assertArrayEquals(new Throwable[]{cleanup}, primary.getSuppressed());
        assertNull(field(holder, "registration"));
        assertSame(state, field(holder, "state"));
        holder.close();
    }

    @Test
    void testDeferredCleanupDoesNotReplaceATranslatedOperationFailure(@TempDir Path tempDir)
        throws Exception {
        ConfigHolder<StrictOwnershipConfig> holder =
            LiteConfig.holder(StrictOwnershipConfig.class).modId("mod").baseDir(tempDir).create();
        Object state = field(holder, "state");
        IllegalArgumentException primary = new IllegalArgumentException("Mutation failed");
        IllegalStateException cleanup = new IllegalStateException("Cleanup failed");
        TestSyncPlatform.setManifestScheduler(() -> {
            throw cleanup;
        });

        LiteConfigException failure = assertThrows(LiteConfigException.class, () -> holder.update(config -> {
            holder.close();
            throw primary;
        }));

        assertSame(primary, failure.getCause());
        assertArrayEquals(new Throwable[]{cleanup}, failure.getSuppressed());
        assertNull(field(holder, "registration"));
        assertSame(state, field(holder, "state"));
    }

    @Test
    void testSuccessfulOperationStillReportsDeferredCleanupFailure(@TempDir Path tempDir)
        throws Exception {
        ConfigHolder<StrictOwnershipConfig> holder =
            LiteConfig.holder(StrictOwnershipConfig.class).modId("mod").baseDir(tempDir).create();
        Object state = field(holder, "state");
        IllegalStateException cleanup = new IllegalStateException("Cleanup failed");
        TestSyncPlatform.setManifestScheduler(() -> {
            throw cleanup;
        });

        assertSame(cleanup, assertThrows(IllegalStateException.class, () -> holder.update(config -> {
            config.value = 7;
            holder.close();
        })));

        assertNull(field(holder, "registration"));
        assertSame(state, field(holder, "state"));
    }

    @Test
    void testDeferredCleanupCannotSuppressAnErrorOntoItself(@TempDir Path tempDir) {
        ConfigHolder<StrictOwnershipConfig> holder =
            LiteConfig.holder(StrictOwnershipConfig.class).modId("mod").baseDir(tempDir).create();
        Error primary = new AssertionError("Shared failure");
        TestSyncPlatform.setManifestScheduler(() -> {
            throw primary;
        });

        assertSame(primary, assertThrows(AssertionError.class, () -> holder.update(config -> {
            holder.close();
            throw primary;
        })));
        assertEquals(0, primary.getSuppressed().length);
    }

    @Test
    void testPendingReturnsTheFullIsolatedRemoteSnapshot(@TempDir Path tempDir) {
        var registration = RegisteredConfigs.create(new ConfigSettings<>(
            TestFixtures.ConfigWithExtension.class, new ConfigScope("mod"), tempDir));
        try (ConfigHolder<TestFixtures.ConfigWithExtension> holder =
                 registration.access()) {
            assertEquals(ConfigType.SERVER, holder.type());
            assertTrue(holder.isActive());
            assertEquals(tempDir.resolve("mod").resolve("with-extension.json5").toAbsolutePath(),
                holder.path().orElseThrow());
            assertTrue(holder.pending().isEmpty());

            var active = holder.copy();
            active.value = 2;
            var desired = holder.copy();
            desired.value = 8;
            registration.state().writing(() -> registration.state().replaceRemote(active, desired, null));

            var pending = holder.pending().orElseThrow();
            assertEquals(8, pending.value);
            assertNotSame(pending, desired);
            assertNotSame(pending, holder.pending().orElseThrow());
            pending.value = 99;
            assertEquals(8, holder.pending().orElseThrow().value);
            assertEquals(2, holder.data().value);
            assertEquals(2, holder.copy().value);

            registration.state().writing(() -> registration.state().replaceRemote(active, null, null));
            assertTrue(holder.pending().isEmpty());
            assertTrue(holder.isActive());
            registration.state().writing(registration.state()::clearRemote);
            assertEquals(1, holder.data().value);
        }
    }

    @Test
    void testRemoteOwnershipRefusesLocalOperationsWithoutPublishingOrWriting(@TempDir Path tempDir)
        throws Exception {
        var registration = RegisteredConfigs.create(new ConfigSettings<>(
            TestFixtures.ConfigWithExtension.class, new ConfigScope("mod"), tempDir));
        try (ConfigHolder<TestFixtures.ConfigWithExtension> holder =
                 registration.access()) {
            Path file = holder.path().orElseThrow();
            String persisted = Files.readString(file);
            var active = holder.copy();
            active.value = 4;
            registration.state().writing(() -> registration.state().replaceRemote(active, null, null));
            Object published = holder.data();
            List<String> events = new ArrayList<>();
            holder.onLoad(state -> events.add("load"));
            holder.onSave(state -> events.add("save"));
            holder.onUpdate(state -> events.add("update"));

            holder.load();
            holder.save();
            assertFalse(holder.update(state -> {
                throw new AssertionError("remote state cannot run a local mutator");
            }).accepted());
            assertFalse(holder.updateAndSave(state -> {
                throw new AssertionError("remote state cannot run a local mutator");
            }).accepted());

            assertSame(published, holder.data());
            assertEquals(persisted, Files.readString(file));
            assertTrue(events.isEmpty());
        }
    }

    @Test
    void testRemoteOwnershipHonorsStrictOperationPolicies(@TempDir Path tempDir) {
        var registration = RegisteredConfigs.create(new ConfigSettings<>(
            StrictOwnershipConfig.class, new ConfigScope("mod"), tempDir));
        try (ConfigHolder<StrictOwnershipConfig> holder = registration.access()) {
            var active = holder.copy();
            registration.state().writing(() -> registration.state().replaceRemote(active, null, null));
            assertEquals(ConfigError.HOLDER_OPERATION_UNSUPPORTED,
                assertThrows(LiteConfigException.class, holder::load).error());
            assertEquals(ConfigError.HOLDER_OPERATION_UNSUPPORTED,
                assertThrows(LiteConfigException.class, holder::save).error());
            assertEquals(ConfigError.HOLDER_OPERATION_UNSUPPORTED,
                assertThrows(LiteConfigException.class, () -> holder.update(state -> state.value++)).error());
            assertEquals(ConfigError.HOLDER_OPERATION_UNSUPPORTED,
                assertThrows(LiteConfigException.class, () -> holder.updateAndSave(state -> state.value++)).error());
        }
    }

    @Test
    void testInactiveWorldOwnershipHasNoPathAndRemoteStateActivatesIt(@TempDir Path tempDir)
        throws Exception {
        var registration = RegisteredConfigs.create(new ConfigSettings<>(
            InactiveWorldConfig.class, new ConfigScope("mod"), tempDir));
        try (ConfigHolder<InactiveWorldConfig> holder = registration.access()) {
            assertEquals(ConfigType.WORLD, holder.type());
            assertFalse(holder.isActive());
            assertTrue(holder.path().isEmpty());
            Object published = holder.data();
            holder.load();
            holder.save();
            assertFalse(holder.update(state -> state.value = 8).accepted());
            assertFalse(holder.updateAndSave(state -> state.value = 8).accepted());
            assertSame(published, holder.data());
            try (var files = Files.list(tempDir)) {
                assertEquals(0, files.count());
            }
            var active = holder.copy();
            active.value = 9;
            registration.state().writing(() -> registration.state().replaceRemote(active, null, null));
            assertTrue(holder.isActive());
            assertEquals(9, holder.data().value);
            assertTrue(holder.path().isEmpty());
            registration.state().writing(registration.state()::clearRemote);
            assertFalse(holder.isActive());
        }
    }

    @Test
    void testInactiveAndRemoteSaveRefusalsSelectWritePolicyNotReadOrUpdatePolicy(@TempDir Path tempDir) {
        var registration = RegisteredConfigs.create(new ConfigSettings<>(
            StrictWorldWriteConfig.class, new ConfigScope("mod"), tempDir));
        try (ConfigHolder<StrictWorldWriteConfig> holder = registration.access()) {
            holder.load();
            assertFalse(holder.update(state -> state.value = 8).accepted());
            assertEquals(ConfigError.HOLDER_OPERATION_UNSUPPORTED,
                assertThrows(LiteConfigException.class, holder::save).error());

            var remote = holder.copy();
            remote.value = 9;
            registration.state().writing(() -> registration.state().replaceRemote(remote, null, null));
            holder.load();
            assertFalse(holder.update(state -> state.value = 8).accepted());
            assertEquals(ConfigError.HOLDER_OPERATION_UNSUPPORTED,
                assertThrows(LiteConfigException.class, holder::save).error());
            assertEquals(9, holder.data().value);
        }
    }

    @Config(name = "strict-ownership", sync = SyncMode.ENABLED, readFailurePolicy = FailurePolicy.STRICT,
        writeFailurePolicy = FailurePolicy.STRICT, updateFailurePolicy = FailurePolicy.STRICT)
    public static class StrictOwnershipConfig {
        public int value = 1;
    }

    @Config(name = "inactive", type = ConfigType.WORLD, writeFailurePolicy = FailurePolicy.FALLBACK)
    public static class InactiveWorldConfig {
        public int value = 1;
    }

    @Config(name = "strict-world-write", type = ConfigType.WORLD,
        writeFailurePolicy = FailurePolicy.STRICT)
    public static class StrictWorldWriteConfig {
        public int value = 1;
    }

    private static Object field(Object target, String name) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static ConfigHolder<TestFixtures.ConfigWithExtension> holder(
        Path tempDir,
        ConfigScope scope
    ) {
        ConfigSettings<TestFixtures.ConfigWithExtension> settings = new ConfigSettings<>(
            TestFixtures.ConfigWithExtension.class,
            scope,
            tempDir.toAbsolutePath().normalize()
        );
        return RegisteredConfigs.create(settings).access();
    }
}
