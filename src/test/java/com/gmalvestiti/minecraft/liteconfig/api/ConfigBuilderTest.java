package com.gmalvestiti.minecraft.liteconfig.api;

import com.gmalvestiti.minecraft.liteconfig.context.ConfigSettings;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Config;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigError;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigScope;
import com.gmalvestiti.minecraft.liteconfig.exception.LiteConfigException;
import com.gmalvestiti.minecraft.liteconfig.support.TestFixtures;
import com.gmalvestiti.minecraft.liteconfig.support.ConfigRegistryIsolation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(ConfigRegistryIsolation.class)
class ConfigBuilderTest {

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
    void testDefaultOwnershipUsesAModScopedServerPath(@TempDir Path tempDir) {
        try (ConfigHolder<TestFixtures.SimpleConfig> holder =
                 LiteConfig.holder(TestFixtures.SimpleConfig.class).modId("owner").baseDir(tempDir).create()) {
            assertEquals(ConfigType.SERVER, holder.type());
            assertTrue(holder.isActive());
            assertTrue(holder.pending().isEmpty());
            assertEquals(tempDir.resolve("owner").resolve("simple.json5").toAbsolutePath(),
                holder.path().orElseThrow());
            assertTrue(Files.isRegularFile(holder.path().orElseThrow()));
        }
    }

    @Test
    void testModIdScopingCanBeDisabledWithoutChangingTheOwningScope(@TempDir Path tempDir) {
        try (ConfigHolder<UnscopedConfig> holder =
                 LiteConfig.holder(UnscopedConfig.class).modId("owner").baseDir(tempDir).create()) {
            assertEquals(tempDir.resolve("unscoped.json5").toAbsolutePath(), holder.path().orElseThrow());
            assertTrue(Files.isRegularFile(holder.path().orElseThrow()));
            assertFalse(Files.exists(tempDir.resolve("owner")));
        }
    }

    @Test
    void testWorldHolderBuildsDefaultsWithoutAWorldOrInstallationFile(@TempDir Path tempDir)
        throws IOException {
        List<Integer> events = new ArrayList<>();
        try (ConfigHolder<WorldConfig> holder =
                 LiteConfig.holder(WorldConfig.class).modId("owner").baseDir(tempDir)
                     .onUpdate(state -> events.add(state.value))
                     .onLoad(state -> events.add(state.value))
                     .onSave(state -> events.add(state.value)).create()) {
            assertEquals(ConfigType.WORLD, holder.type());
            assertFalse(holder.isActive());
            assertEquals(7, holder.data().value);
            assertTrue(holder.path().isEmpty());
            assertTrue(holder.pending().isEmpty());
            assertFalse(holder.update(state -> state.value = 9).accepted());
            holder.load();
            holder.save();
            assertEquals(7, holder.data().value);
            assertTrue(events.isEmpty());
            try (var files = Files.list(tempDir)) {
                assertEquals(0, files.count());
            }
        }
    }

    @Test
    void testNullEventsRegisterNothingAndKeepTheBuilder(@TempDir Path tempDir) {
        ConfigBuilder<TestFixtures.SimpleConfig> builder =
            LiteConfig.holder(TestFixtures.SimpleConfig.class).modId("mod").baseDir(tempDir);
        assertSame(builder, builder.onUpdate(null));
        assertSame(builder, builder.onLoad(null));
        assertSame(builder, builder.onSave(null));
        try (ConfigHolder<TestFixtures.SimpleConfig> holder = builder.create()) {
            assertSame(ConfigSubscription.NONE, holder.onUpdate(null));
            assertSame(ConfigSubscription.NONE, holder.onLoad(null));
            assertSame(ConfigSubscription.NONE, holder.onSave(null));
            assertTrue(holder.updateAndSave(state -> state.value = 2).accepted());
            holder.load();
            assertEquals(2, holder.data().value);
        }
    }

    @Test
    void testAnnotationControlsReadOnlyAcrossBuilders(@TempDir Path tempDir) {
        ConfigHolder<TestFixtures.SimpleConfig> mutable = LiteConfig.holder(TestFixtures.SimpleConfig.class)
            .modId("mod").baseDir(tempDir.toString()).create();
        assertTrue(mutable.update(config -> config.value = 5).accepted());

        ConfigHolder<TestFixtures.ReadOnlyConfigWithExtension> readOnly =
            LiteConfig.holder(TestFixtures.ReadOnlyConfigWithExtension.class)
                .modId("mod").baseDir(tempDir).create();
        assertFalse(readOnly.update(config -> config.value = 6).accepted(),
            "the annotation must refuse changes");
        ConfigHolder<TestFixtures.ReadOnlyConfigWithExtension> second =
            LiteConfig.holder(TestFixtures.ReadOnlyConfigWithExtension.class)
                .modId("mod").baseDir(tempDir).create();
        assertSame(readOnly.data(), second.data());
        assertSame(readOnly, second);
        assertFalse(second.update(config -> config.value = 7).accepted(),
            "a second builder must not bypass the registration's read-only rule");
        assertFalse(second.updateAndSave(config -> config.value = 8).accepted());
        assertEquals(1, readOnly.data().value);
    }

    @Test
    void testReadOnlyHolderThrowsUnderAStrictUpdatePolicy(@TempDir Path tempDir) {
        ConfigHolder<TestFixtures.StrictReadOnlyConfigWithExtension> readOnly =
            LiteConfig.holder(TestFixtures.StrictReadOnlyConfigWithExtension.class)
            .modId("mod").baseDir(tempDir.toString())
            .create();

        assertThrows(LiteConfigException.class, () -> readOnly.update(config -> config.value = 5));
    }

    @Test
    void testDistinctBuildersReturnTheSameHolderForOneRegistration(@TempDir Path tempDir) {
        ConfigHolder<TestFixtures.SimpleConfig> first = LiteConfig.holder(TestFixtures.SimpleConfig.class)
            .modId("mod").baseDir(tempDir.toString()).create();
        ConfigHolder<TestFixtures.SimpleConfig> second = LiteConfig.holder(TestFixtures.SimpleConfig.class)
            .modId("mod").baseDir(tempDir.toString()).create();

        assertSame(first, second);
        assertTrue(first.update(config -> config.value = 42).accepted());

        assertEquals(42, second.data().value);
    }

    @Test
    void testRepeatedCreateReturnsTheSameHolderAndAppendsBuilderEvents(@TempDir Path tempDir) {
        List<String> events = new ArrayList<>();
        ConfigBuilder<TestFixtures.SimpleConfig> builder =
            LiteConfig.holder(TestFixtures.SimpleConfig.class).modId("mod").baseDir(tempDir)
                .onUpdate(state -> events.add("first-" + state.value));
        try (ConfigHolder<TestFixtures.SimpleConfig> first = builder.create()) {
            assertSame(first, builder.create());
            ConfigHolder<TestFixtures.SimpleConfig> later =
                LiteConfig.holder(TestFixtures.SimpleConfig.class).modId("mod").baseDir(tempDir)
                    .onUpdate(state -> events.add("later-" + state.value)).create();
            assertSame(first, later);

            later.update(state -> state.value = 2);

            assertEquals(List.of("first-2", "first-2", "later-2"), events);
        }
    }

    @Test
    void testSameBuilderCreatesAFreshHolderAfterClose(@TempDir Path tempDir) {
        ConfigBuilder<TestFixtures.SimpleConfig> builder =
            LiteConfig.holder(TestFixtures.SimpleConfig.class).modId("mod").baseDir(tempDir);
        ConfigHolder<TestFixtures.SimpleConfig> original = builder.create();
        original.update(state -> state.value = 9);
        original.close();

        try (ConfigHolder<TestFixtures.SimpleConfig> reopened = builder.create()) {
            assertNotSame(original, reopened);
            assertEquals(1, reopened.data().value);
            assertEquals(ConfigError.HOLDER_CLOSED,
                assertThrows(LiteConfigException.class, original::copy).error());
            original.close();
            assertTrue(reopened.update(state -> state.value = 7).accepted());
        }
    }

    @Test
    void testOnUpdateFiresOnAcceptedUpdateButNotOnBuildLoad(@TempDir Path tempDir) {
        List<Integer> seen = new ArrayList<>();

        ConfigHolder<TestFixtures.SimpleConfig> holder =
            LiteConfig.holder(TestFixtures.SimpleConfig.class)
                .modId("mod")
                .baseDir(tempDir.toString())
                .onUpdate(state -> {
                    throw new IllegalStateException("a broken listener must not stop the others");
                })
                .onUpdate(state -> seen.add(state.value))
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
                .onLoad(state -> seen.add(state.value))
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
                .onSave(state -> seen.add(state.value))
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

        holder.onUpdate(state -> updates.add(state.value));
        holder.onLoad(state -> loads.add(state.value));
        holder.onSave(state -> saves.add(state.value));

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
            state -> updates.add(state.value));
        ConfigSubscription second = holder.onUpdate(
            state -> updates.add(state.value * 10));

        holder.update(state -> state.value = 2);
        explicit.close();
        explicit.close();
        holder.update(state -> state.value = 3);
        second.close();
        holder.update(state -> state.value = 4);

        assertEquals(List.of(2, 20, 30), updates);
    }

    @Test
    void testClosingHolderRemovesAllListeners(@TempDir Path tempDir) {
        List<Integer> updates = new ArrayList<>();
        ConfigHolder<TestFixtures.SimpleConfig> holder =
            LiteConfig.holder(TestFixtures.SimpleConfig.class)
                .modId("mod")
                .baseDir(tempDir)
                .onUpdate(state -> updates.add(state.value))
                .create();

        TestFixtures.SimpleConfig published = holder.data();
        holder.close();
        assertSame(published, holder.data());
        assertEquals(ConfigError.HOLDER_CLOSED,
            assertThrows(LiteConfigException.class, holder::metadata).error());

        LiteConfigException failure = assertThrows(
            LiteConfigException.class,
            () -> holder.update(state -> state.value = 2));

        assertTrue(updates.isEmpty());
        assertEquals(ConfigError.HOLDER_CLOSED, failure.error());
    }

    @Test
    void testEventsRunOnceSynchronouslyOnTheCaller(@TempDir Path tempDir) {
        List<Integer> updates = new ArrayList<>();
        Thread caller = Thread.currentThread();
        try (ConfigHolder<TestFixtures.SimpleConfig> holder =
                 LiteConfig.holder(TestFixtures.SimpleConfig.class)
                     .modId("mod").baseDir(tempDir)
                     .onUpdate(state -> {
                         assertEquals(caller, Thread.currentThread());
                         updates.add(state.value);
                     }).create()) {
            holder.update(state -> state.value = 2);
            assertEquals(List.of(2), updates);
        }
    }

    @Test
    void testEventsReceiveIsolatedPublishedSnapshots(@TempDir Path tempDir) {
        List<TestFixtures.SimpleConfig> updates = new ArrayList<>();
        try (ConfigHolder<TestFixtures.SimpleConfig> holder =
                 LiteConfig.holder(TestFixtures.SimpleConfig.class)
                     .modId("mod").baseDir(tempDir)
                     .onUpdate(updates::add).create()) {

            holder.update(state -> state.value = 2);
            holder.update(state -> state.value = 3);

            assertEquals(3, holder.data().value);
            assertEquals(List.of(2, 3), updates.stream().map(state -> state.value).toList());
            updates.getFirst().value = 99;
            assertEquals(3, holder.copy().value, "an old published view cannot mutate canonical state");
        }
    }

    @Test
    void testSynchronousEventsCanStartNestedOperationsAfterPublication(@TempDir Path tempDir) {
        try (ConfigHolder<TestFixtures.SimpleConfig> holder =
                 LiteConfig.holder(TestFixtures.SimpleConfig.class).modId("mod").baseDir(tempDir).create()) {
            List<Integer> events = new ArrayList<>();
            holder.onUpdate(state -> {
                assertSame(state, holder.data());
                events.add(state.value);
                if (state.value == 4) {
                    assertTrue(holder.update(next -> next.value = 99).accepted());
                }
            });
            assertTrue(holder.update(state -> state.value = 4).accepted());
            assertEquals(99, holder.data().value);
            assertEquals(List.of(4, 99), events);
        }
    }

    @Test
    void testClosingHolderRemovesEventsFromASharedRegistration(@TempDir Path tempDir) {
        List<Integer> updates = new ArrayList<>();
        ConfigHolder<TestFixtures.SimpleConfig> holder =
            LiteConfig.holder(TestFixtures.SimpleConfig.class)
                .modId("mod").baseDir(tempDir)
                .onUpdate(state -> updates.add(state.value)).create();
        try (ConfigHolder<TestFixtures.SimpleConfig> other =
                 LiteConfig.holder(TestFixtures.SimpleConfig.class).modId("mod").baseDir(tempDir).create()) {
            holder.update(state -> state.value = 2);
            holder.close();
            assertEquals(ConfigError.HOLDER_CLOSED,
                assertThrows(LiteConfigException.class,
                    () -> other.update(state -> state.value = 3)).error());
            assertSame(ConfigSubscription.NONE, other.onUpdate(state -> updates.add(state.value)));
            assertEquals(List.of(2), updates);
        } finally {
            holder.close();
        }
    }

    @Test
    void testDoesNotNotifyListenersWhenALoadFallsBackToDefaults(@TempDir Path tempDir) throws IOException {
        List<Integer> seen = new ArrayList<>();

        ConfigHolder<TestFixtures.SimpleConfig> holder =
            LiteConfig.holder(TestFixtures.SimpleConfig.class)
                .modId("mod")
                .baseDir(tempDir.toString())
                .onLoad(state -> seen.add(state.value))
                .create();

        Files.writeString(tempDir.resolve("mod").resolve("simple.json5"), "{ not a config");
        holder.load();

        assertTrue(seen.isEmpty(), "a swallowed failure is not a change worth announcing");
    }

    @Test
    void testDoesNotNotifyListenersWhenAnUpdateIsRejected(@TempDir Path tempDir) {
        List<Integer> seen = new ArrayList<>();

        ConfigHolder<TestFixtures.ConfigWithExtension> holder =
            LiteConfig.holder(TestFixtures.ConfigWithExtension.class)
                .modId("mod")
                .baseDir(tempDir.toString())
                .onUpdate(state -> seen.add(state.value))
                .create();

        assertFalse(holder.update(config -> config.value = -1).accepted());
        assertTrue(seen.isEmpty());
    }

    @Test
    void testRunsTheConfigsOwnMigrationsWhenTheHolderLoads(@TempDir Path tempDir) throws IOException {
        Files.createDirectories(tempDir.resolve("mod"));
        Files.writeString(tempDir.resolve("mod").resolve("versioned.json5"), "{\"hudScale\":7}");

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

    @Config(name = "unscoped", modIdDirectory = false)
    public static class UnscopedConfig {
        public int value = 1;
    }

    @Config(name = "world", type = ConfigType.WORLD)
    public static class WorldConfig {
        public int value = 7;
    }
}
