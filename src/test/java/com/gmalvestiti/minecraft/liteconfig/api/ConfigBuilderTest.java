package com.gmalvestiti.minecraft.liteconfig.api;

import com.gmalvestiti.minecraft.liteconfig.async.ConfigEventExecutors;
import com.gmalvestiti.minecraft.liteconfig.context.ConfigSettings;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigError;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigScope;
import com.gmalvestiti.minecraft.liteconfig.exception.LiteConfigException;
import com.gmalvestiti.minecraft.liteconfig.support.TestFixtures;
import com.gmalvestiti.minecraft.liteconfig.support.ConfigRegistryIsolation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(ConfigRegistryIsolation.class)
class ConfigBuilderTest {

    @BeforeEach
    void configureLogicalThreads() {
        ConfigEventExecutors.setClientMainThread(Runnable::run);
        ConfigEventExecutors.setServerMainThread(Runnable::run);
    }

    @Test
    void testResolvesDefaultSettings(@TempDir Path tempDir) {
        ConfigSettings<TestFixtures.SimpleConfig> settings =
            LiteConfig.holder(TestFixtures.SimpleConfig.class)
                .modId("mod")
                .baseDir(tempDir.toString())
                .settings();

        assertEquals(new ConfigScope("mod"), settings.scope());
        assertEquals(tempDir.toAbsolutePath().normalize(), settings.baseDirectory());
    }

    @Test
    void testCreatesMutableAndReadOnlyHolders(@TempDir Path tempDir) {
        ConfigHolder<TestFixtures.SimpleConfig> mutable = LiteConfig.holder(TestFixtures.SimpleConfig.class)
            .modId("mod").baseDir(tempDir.toString()).create();
        assertTrue(mutable.update(config -> config.value = 5).accepted());

        ConfigHolder<TestFixtures.SimpleConfig> readOnly = LiteConfig.holder(TestFixtures.SimpleConfig.class)
            .modId("mod").baseDir(tempDir.toString()).readOnly().create();
        assertFalse(readOnly.update(config -> config.value = 6).accepted(),
            "readOnly() must configure a holder that refuses to change values");
    }

    @Test
    void testReadOnlyHolderThrowsUnderAStrictUpdatePolicy(@TempDir Path tempDir) {
        ConfigHolder<TestFixtures.StrictUpdateConfigWithExtension> readOnly =
            LiteConfig.holder(TestFixtures.StrictUpdateConfigWithExtension.class)
            .modId("mod").baseDir(tempDir.toString())
            .readOnly()
            .create();

        assertThrows(LiteConfigException.class, () -> readOnly.update(config -> config.value = 5));
    }

    @Test
    void testHoldersOfOneConfigShareTheSameState(@TempDir Path tempDir) {
        ConfigHolder<TestFixtures.SimpleConfig> first = LiteConfig.holder(TestFixtures.SimpleConfig.class)
            .modId("mod").baseDir(tempDir.toString()).create();
        ConfigHolder<TestFixtures.SimpleConfig> second = LiteConfig.holder(TestFixtures.SimpleConfig.class)
            .modId("mod").baseDir(tempDir.toString()).create();

        assertTrue(first.update(config -> config.value = 42).accepted());

        assertEquals(42, second.data().value);
    }

    @Test
    void testOnUpdateFiresOnAcceptedUpdateButNotOnBuildLoad(@TempDir Path tempDir) {
        List<Integer> seen = new ArrayList<>();

        ConfigHolder<TestFixtures.SimpleConfig> holder =
            LiteConfig.holder(TestFixtures.SimpleConfig.class)
                .modId("mod")
                .baseDir(tempDir.toString())
                .onUpdate(ConfigSide.CLIENT, state -> {
                    throw new IllegalStateException("a broken listener must not stop the others");
                })
                .onUpdate(ConfigSide.CLIENT, state -> seen.add(state.value))
                .create();

        assertTrue(seen.isEmpty(), "building a holder is not a change");

        holder.updateAndSave(config -> config.value = 5);
        holder.load();

        assertEquals(List.of(5), seen);
    }

    @Test
    void testOnLoadFiresAfterSuccessfulLoadOnly(@TempDir Path tempDir) {
        List<Integer> seen = new ArrayList<>();

        ConfigHolder<TestFixtures.SimpleConfig> holder =
            LiteConfig.holder(TestFixtures.SimpleConfig.class)
                .modId("mod")
                .baseDir(tempDir.toString())
                .onLoad(ConfigSide.CLIENT, state -> seen.add(state.value))
                .create();

        assertTrue(seen.isEmpty(), "build-time load must not fire onLoad");

        holder.update(config -> config.value = 7);
        holder.load();

        assertEquals(List.of(1), seen, "onLoad fires once, with the value read from disk");
    }

    @Test
    void testOnSaveFiresAfterEveryPersistOperation(@TempDir Path tempDir) {
        List<Integer> seen = new ArrayList<>();

        ConfigHolder<TestFixtures.SimpleConfig> holder =
            LiteConfig.holder(TestFixtures.SimpleConfig.class)
                .modId("mod")
                .baseDir(tempDir.toString())
                .onSave(ConfigSide.CLIENT, state -> seen.add(state.value))
                .create();

        holder.update(config -> config.value = 3);
        holder.save();
        holder.updateAndSave(config -> config.value = 9);

        assertEquals(List.of(3, 9), seen);
    }

    @Test
    void testRegistersListenersAfterHolderCreation(@TempDir Path tempDir) {
        List<Integer> updates = new ArrayList<>();
        List<Integer> loads   = new ArrayList<>();
        List<Integer> saves   = new ArrayList<>();

        ConfigHolder<TestFixtures.SimpleConfig> holder =
            LiteConfig.holder(TestFixtures.SimpleConfig.class)
                .modId("mod")
                .baseDir(tempDir.toString())
                .create();

        holder.onUpdate(ConfigSide.CLIENT, state -> updates.add(state.value));
        holder.onLoad(ConfigSide.CLIENT, state -> loads.add(state.value));
        holder.onSave(ConfigSide.CLIENT, state -> saves.add(state.value));

        holder.updateAndSave(config -> config.value = 6);
        holder.load();

        assertEquals(List.of(6), updates);
        assertEquals(List.of(6), loads, "load re-reads the file written by updateAndSave");
        assertEquals(List.of(6), saves);
    }

    @Test
    void testRemovesSubscriptionsIndependently(@TempDir Path tempDir) {
        List<Integer> updates = new ArrayList<>();
        ConfigHolder<TestFixtures.SimpleConfig> holder =
            LiteConfig.holder(TestFixtures.SimpleConfig.class)
                .modId("mod")
                .baseDir(tempDir)
                .create();
        ConfigSubscription explicit = holder.onUpdate(
            ConfigSide.CLIENT, state -> updates.add(state.value));
        ConfigSubscription second = holder.onUpdate(
            ConfigSide.CLIENT, state -> updates.add(state.value * 10));

        holder.update(state -> state.value = 2);
        explicit.close();
        explicit.close();
        holder.update(state -> state.value = 3);
        second.close();
        holder.update(state -> state.value = 4);

        assertEquals(List.of(2, 20, 30), updates);
    }

    @Test
    void testClosingHolderRemovesAllOwnedListeners(@TempDir Path tempDir) {
        List<Integer> updates = new ArrayList<>();
        ConfigHolder<TestFixtures.SimpleConfig> holder =
            LiteConfig.holder(TestFixtures.SimpleConfig.class)
                .modId("mod")
                .baseDir(tempDir)
                .onUpdate(ConfigSide.CLIENT, state -> updates.add(state.value))
                .create();

        holder.close();
        assertEquals(1, holder.data().value);
        assertEquals(TestFixtures.SimpleConfig.class, holder.metadata().type());

        LiteConfigException failure = assertThrows(
            LiteConfigException.class,
            () -> holder.update(state -> state.value = 2));

        assertTrue(updates.isEmpty());
        assertEquals(ConfigError.HOLDER_CLOSED, failure.error());
    }

    @Test
    void testDispatchesListenersToBothConfigSides(@TempDir Path tempDir) {
        ArrayDeque<Runnable> clientThread = new ArrayDeque<>();
        ArrayDeque<Runnable> serverThread = new ArrayDeque<>();
        List<Integer> updates = new ArrayList<>();
        ConfigEventExecutors.setClientMainThread(clientThread::addLast);
        ConfigEventExecutors.setServerMainThread(serverThread::addLast);
        try {
            ConfigHolder<TestFixtures.SimpleConfig> holder =
                LiteConfig.holder(TestFixtures.SimpleConfig.class)
                    .modId("mod")
                    .baseDir(tempDir)
                    .onUpdate(ConfigSide.BOTH, state -> updates.add(state.value))
                    .create();

            holder.update(state -> state.value = 2);

            assertTrue(updates.isEmpty(), "the config worker must not invoke game listeners");
            clientThread.removeFirst().run();
            serverThread.removeFirst().run();
            assertEquals(List.of(2, 2), updates);
        } finally {
            ConfigEventExecutors.setClientMainThread(Runnable::run);
            ConfigEventExecutors.setServerMainThread(Runnable::run);
        }
    }

    @Test
    void testClosingHolderCancelsQueuedListeners(@TempDir Path tempDir) {
        ArrayDeque<Runnable> gameThread = new ArrayDeque<>();
        List<Integer> updates = new ArrayList<>();
        ConfigEventExecutors.setClientMainThread(gameThread::addLast);
        try {
            ConfigHolder<TestFixtures.SimpleConfig> holder =
                LiteConfig.holder(TestFixtures.SimpleConfig.class)
                    .modId("mod")
                    .baseDir(tempDir)
                    .onUpdate(ConfigSide.CLIENT, state -> updates.add(state.value))
                    .create();

            holder.update(state -> state.value = 2);
            holder.close();
            gameThread.removeFirst().run();

            assertTrue(updates.isEmpty());
        } finally {
            ConfigEventExecutors.setClientMainThread(Runnable::run);
        }
    }

    @Test
    void testDoesNotNotifyListenersWhenALoadFallsBackToDefaults(@TempDir Path tempDir) throws IOException {
        List<Integer> seen = new ArrayList<>();

        ConfigHolder<TestFixtures.SimpleConfig> holder =
            LiteConfig.holder(TestFixtures.SimpleConfig.class)
                .modId("mod")
                .baseDir(tempDir.toString())
                .onLoad(ConfigSide.CLIENT, state -> seen.add(state.value))
                .create();

        Files.writeString(tempDir.resolve("simple.json5"), "{ not a config");
        holder.load();

        assertTrue(seen.isEmpty(), "a swallowed failure is not a change worth announcing");
    }

    @Test
    void testDoesNotNotifyListenersWhenAnUpdateIsRejected(@TempDir Path tempDir) {        List<Integer> seen = new ArrayList<>();

        ConfigHolder<TestFixtures.ConfigWithExtension> holder =
            LiteConfig.holder(TestFixtures.ConfigWithExtension.class)
                .modId("mod")
                .baseDir(tempDir.toString())
                .onUpdate(ConfigSide.CLIENT, state -> seen.add(state.value))
                .create();

        assertFalse(holder.update(config -> config.value = -1).accepted());
        assertTrue(seen.isEmpty());
    }

    @Test
    void testRunsTheConfigsOwnMigrationsWhenTheHolderLoads(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("versioned.json5"), "{\"hudScale\":7}");

        ConfigHolder<TestFixtures.VersionedConfig> holder =
            LiteConfig.holder(TestFixtures.VersionedConfig.class)
                .modId("mod")
                .baseDir(tempDir.toString())
                .create();

        assertEquals(7, holder.data().hud.scale);
        assertEquals("default", holder.data().profile);
    }

    @Test
    void testRejectsAConfigWithBrokenMigrationDeclarations(@TempDir Path tempDir) {
        ConfigBuilder<TestFixtures.InstanceMigrationConfig> builder =
            LiteConfig.holder(TestFixtures.InstanceMigrationConfig.class)
                .modId("mod")
                .baseDir(tempDir.toString());

        assertThrows(LiteConfigException.class, builder::create);
    }

    @Test
    void testRejectsMissingArgumentsAndInvalidPath() {
        assertThrows(LiteConfigException.class, () -> LiteConfig.holder(null));
        assertThrows(
            LiteConfigException.class,
            () -> LiteConfig.holder(TestFixtures.SimpleConfig.class).baseDir("bad\u0000path")
        );
        assertThrows(
            LiteConfigException.class,
            () -> LiteConfig.holder(TestFixtures.SimpleConfig.class).modId("mod").baseDir("bad\u0000path")
        );
        assertThrows(
            LiteConfigException.class,
            () -> LiteConfig.holder(TestFixtures.SimpleConfig.class).modId(" ")
        );
        assertThrows(
            LiteConfigException.class,
            () -> LiteConfig.holder(TestFixtures.SimpleConfig.class).settings()
        );
    }
}
