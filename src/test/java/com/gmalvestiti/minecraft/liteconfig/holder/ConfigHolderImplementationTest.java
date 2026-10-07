package com.gmalvestiti.minecraft.liteconfig.holder;

import com.gmalvestiti.minecraft.liteconfig.api.ConfigHolder;
import com.gmalvestiti.minecraft.liteconfig.api.LiteConfig;
import com.gmalvestiti.minecraft.liteconfig.api.ConfigSide;
import com.gmalvestiti.minecraft.liteconfig.api.ConfigSubscription;
import com.gmalvestiti.minecraft.liteconfig.api.metadata.ConfigMetadata;
import com.gmalvestiti.minecraft.liteconfig.engine.ConfigEventThreads;
import com.gmalvestiti.minecraft.liteconfig.context.ConfigSettings;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigScope;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigError;
import com.gmalvestiti.minecraft.liteconfig.exception.LiteConfigException;
import com.gmalvestiti.minecraft.liteconfig.support.ConfigRegistryIsolation;
import com.gmalvestiti.minecraft.liteconfig.support.RegisteredConfigs;
import com.gmalvestiti.minecraft.liteconfig.support.TestFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(ConfigRegistryIsolation.class)
class ConfigHolderImplementationTest {

    @BeforeEach
    void configureClientThread() {
        ConfigEventThreads.setClientMainThread(Runnable::run);
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
    void testCreatesTheConfigFileDuringInitialization(@TempDir Path tempDir) throws Exception {
        ConfigScope scope = spy(new ConfigScope("mod"));

        holder(tempDir, scope);

        assertTrue(Files.readString(tempDir.resolve("with-extension.json5")).contains("\"value\": 1"));
    }

    @Test
    void testAdoptsAnExistingValidFileDuringInitialization(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("with-extension.json5"), "{\"value\":7}");
        ConfigScope scope = spy(new ConfigScope("mod"));

        ConfigHolder<TestFixtures.ConfigWithExtension> holder = holder(tempDir, scope);

        assertEquals(7, holder.data().value, "a valid file must seed the holder without calling load()");
        assertTrue(Files.readString(tempDir.resolve("with-extension.json5")).contains("\"value\": 7"),
            "adopted values must survive the write-back");
    }

    @Test
    void testBacksUpAnInvalidFileAndPersistsDefaultsDuringInitialization(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("with-extension.json5");
        Files.writeString(file, "{\"value\":-5}");
        ConfigScope scope = spy(new ConfigScope("mod"));

        ConfigHolder<TestFixtures.ConfigWithExtension> holder = holder(tempDir, scope);

        assertEquals(1, holder.data().value);
        assertTrue(Files.readString(file).contains("\"value\": 1"));
        try (var entries = Files.list(tempDir)) {
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
        holder.onUpdate(ConfigSide.CLIENT, state -> {
            assertSame(gameThread, Thread.currentThread());
            assertEquals(state.value, holder.data().value);
            notifications.add("update-" + state.value);
        });
        holder.onSave(ConfigSide.CLIENT, state -> {
            assertSame(gameThread, Thread.currentThread());
            notifications.add("save-" + state.value);
            try {
                assertTrue(Files.readString(tempDir.resolve("with-extension.json5"))
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
        assertTrue(Files.readString(tempDir.resolve("with-extension.json5"))
            .contains("\"value\": 2"));
    }

    @Test
    void testClosedHolderAndSubscriptionDropTheirReferences(@TempDir Path tempDir) throws Exception {
        ConfigHolder<TestFixtures.ConfigWithExtension> holder =
            holder(tempDir, new ConfigScope("mod"));
        ConfigSubscription subscription = holder.onUpdate(ConfigSide.CLIENT, state -> {});

        holder.close();
        holder.close();
        subscription.close();

        assertNull(field(holder, "registration"));
        assertNull(field(holder, "state"));
        assertTrue(((Collection<?>) field(holder, "ownedSubscriptions")).isEmpty());
        assertNull(field(subscription, "closeAction"));
        assertNull(field(subscription, "retained"));
        assertNull(field(subscription, "owner"));
        assertThrows(NullPointerException.class, holder::data);
        assertEquals(ConfigError.HOLDER_CLOSED,
            assertThrows(LiteConfigException.class, holder::metadata).error());
        assertEquals(ConfigError.HOLDER_CLOSED,
            assertThrows(LiteConfigException.class, holder::copy).error());
        assertSame(ConfigSubscription.NONE, holder.onUpdate(ConfigSide.CLIENT, state -> {}));
        assertSame(ConfigSubscription.NONE, holder.onLoad(ConfigSide.CLIENT, state -> {}));
        assertSame(ConfigSubscription.NONE, holder.onSave(ConfigSide.CLIENT, state -> {}));
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
    void testClosingDropsQueuedListenerStateBeforeGameThreadRuns(@TempDir Path tempDir) throws Exception {
        ArrayDeque<Runnable> gameThread = new ArrayDeque<>();
        ConfigEventThreads.setClientMainThread(gameThread::addLast);
        try {
            ConfigHolder<TestFixtures.ConfigWithExtension> holder =
                holder(tempDir, new ConfigScope("mod"));
            holder.onUpdate(ConfigSide.CLIENT, state -> {
                throw new AssertionError("A closed listener must not run");
            });
            holder.update(state -> state.value = 2);
            Runnable queued = gameThread.getFirst();
            Field task = Arrays.stream(queued.getClass().getDeclaredFields())
                .filter(candidate -> Runnable.class.isAssignableFrom(candidate.getType()))
                .findFirst().orElseThrow();
            task.setAccessible(true);
            Object notification = task.get(queued);

            holder.close();

            assertNull(field(notification, "action"));
            assertTrue(((Collection<?>) field(notification, "pending")).isEmpty());
            queued.run();
        } finally {
            ConfigEventThreads.setClientMainThread(Runnable::run);
        }
    }

    @Test
    void testClosingInsideTheMutatorFinishesAcceptedWorkThenDropsReferences(@TempDir Path tempDir)
        throws Exception {
        var registration = RegisteredConfigs.create(new ConfigSettings<>(
            TestFixtures.ConfigWithExtension.class, new ConfigScope("mod"), tempDir));
        ConfigHolderImplementation<TestFixtures.ConfigWithExtension> closingHolder =
            new ConfigHolderImplementation<>(registration, false);

        assertTrue(closingHolder.update(state -> {
            state.value = 8;
            closingHolder.close();
            assertEquals(1, closingHolder.data().value,
                "close during an operation must retain the current state until it finishes");
            assertEquals(ConfigError.HOLDER_CLOSED,
                assertThrows(LiteConfigException.class, closingHolder::metadata).error());
        }).accepted());

        assertNull(field(closingHolder, "registration"));
        assertNull(field(closingHolder, "state"));
        assertEquals(8, registration.state().published().value);
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
        return new ConfigHolderImplementation<>(
            RegisteredConfigs.create(settings), false);
    }
}
