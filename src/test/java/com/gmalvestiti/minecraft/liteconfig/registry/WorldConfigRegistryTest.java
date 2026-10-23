package com.gmalvestiti.minecraft.liteconfig.registry;

import com.gmalvestiti.minecraft.liteconfig.api.ConfigExtension;
import com.gmalvestiti.minecraft.liteconfig.api.ConfigHolder;
import com.gmalvestiti.minecraft.liteconfig.api.ConfigType;
import com.gmalvestiti.minecraft.liteconfig.api.LiteConfig;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Config;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Entry;
import com.gmalvestiti.minecraft.liteconfig.context.ConfigSettings;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigScope;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigError;
import com.gmalvestiti.minecraft.liteconfig.exception.LiteConfigException;
import com.gmalvestiti.minecraft.liteconfig.support.ConfigRegistryIsolation;
import com.gmalvestiti.minecraft.liteconfig.support.TestSyncPlatform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(ConfigRegistryIsolation.class)
class WorldConfigRegistryTest {

    @Test
    void restartSelectionsAreIsolatedAndAppliedAtEachWorldLoad(@TempDir Path directory) throws Exception {
        Path first = directory.resolve("first");
        Path second = directory.resolve("second");
        try (ConfigHolder<RestartWorld> holder = LiteConfig.holder(RestartWorld.class)
            .modId("mod").baseDir(directory.resolve("config")).create()) {
            ConfigRegistry.activateWorld(first);
            assertTrue(holder.updateAndSave(value -> {
                value.restart = 8;
                value.immediate = 2;
            }).accepted());
            assertEquals(5, holder.data().restart);
            assertEquals(8, holder.pending().orElseThrow().restart);
            holder.load();
            assertEquals(5, holder.data().restart);
            ConfigRegistry.activateWorld(second);
            assertEquals(5, holder.data().restart);
            assertEquals(1, holder.data().immediate);
            assertTrue(holder.pending().isEmpty());
            assertTrue(holder.updateAndSave(value -> value.restart = 11).accepted());
            ConfigRegistry.activateWorld(first);
            assertEquals(8, holder.data().restart);
            assertEquals(2, holder.data().immediate);
            assertTrue(holder.pending().isEmpty());
            assertTrue(holder.update(value -> value.restart = 19).accepted());
            ConfigRegistry.deactivateWorld();
            assertTrue(holder.pending().isEmpty());
            ConfigRegistry.activateWorld(second);
            assertEquals(11, holder.data().restart);
            ConfigRegistry.activateWorld(first);
            assertEquals(8, holder.data().restart, "unsaved selections do not leak into the next world load");
        }
    }

    @Config(name = "restart-world", type = ConfigType.WORLD)
    static class RestartWorld {
        @Entry(restart = true)
        int restart = 5;
        int immediate = 1;
    }

    @Config(name = "world", path = "rules", type = ConfigType.WORLD)
    static class WorldConfig {
        static final AtomicReference<Object> operationMonitor = new AtomicReference<>();
        static final AtomicBoolean fieldListenerHeldMonitor = new AtomicBoolean();

        @Entry(listener = "changed")
        public int value = 5;

        private void changed(Integer before, Integer after, boolean fromSync) {
            Object monitor = operationMonitor.get();
            if (monitor != null && Thread.holdsLock(monitor)) {
                fieldListenerHeldMonitor.set(true);
            }
        }
    }

    @Config(name = "hooked", type = ConfigType.WORLD)
    static class HookedWorldConfig implements ConfigExtension {
        public int value = 5;

        @Override
        public void afterLoad() {
            value++;
        }
    }

    @Config(name = "broken", type = ConfigType.WORLD)
    static class BrokenWorldHook implements ConfigExtension {
        public int value = 5;

        @Override
        public void afterLoad() {
            value = 99;
            throw new IllegalStateException("load hook failed");
        }
    }

    @Config(name = "world", path = "rules", type = ConfigType.WORLD)
    static class ConflictingWorldConfig {
        public int value = 13;
    }

    @Config(name = "world", path = "rules")
    static class InstallationConfig {
        public int value = 1;
    }

    @AfterEach
    void clearWorld() {
        ConfigRegistry.deactivateWorld();
        WorldConfig.operationMonitor.set(null);
        WorldConfig.fieldListenerHeldMonitor.set(false);
    }

    @Test
    void testStableHolderSwitchesWorldFilesAndRestoresDefaults(@TempDir Path directory) throws Exception {
        Path installation = directory.resolve("installation");
        Path firstWorld = directory.resolve("first");
        Path secondWorld = directory.resolve("second");
        Path firstFile = worldFile(firstWorld);
        Files.createDirectories(firstFile.getParent());
        Files.writeString(firstFile, "{\"value\":11}");
        RegisteredConfig<WorldConfig> registration = ConfigRegistry.register(settings(WorldConfig.class, installation));
        List<Integer> loads = new ArrayList<>();
        AtomicBoolean holderEventHeldMonitor = new AtomicBoolean();
        WorldConfig.operationMonitor.set(registration.state().operationLock());

        try (ConfigHolder<WorldConfig> holder = LiteConfig.holder(WorldConfig.class)
            .modId("mod").baseDir(installation).onLoad(config -> {
                loads.add(config.value);
                holderEventHeldMonitor.compareAndSet(false,
                    Thread.holdsLock(registration.state().operationLock()));
            }).create()) {
            assertEquals(5, holder.data().value);
            assertFalse(registration.isActive());
            assertFalse(registration.isLocallyWritable());
            assertFalse(Files.exists(installation));

            ConfigRegistry.activateWorld(firstWorld);
            assertEquals(11, holder.data().value);
            assertFalse(holderEventHeldMonitor.get());
            assertFalse(WorldConfig.fieldListenerHeldMonitor.get());
            assertTrue(registration.isActive());
            assertTrue(registration.isLocallyWritable());
            assertEquals(List.of(11), loads);
            assertTrue(holder.updateAndSave(config -> config.value = 19).accepted());

            ConfigRegistry.activateWorld(secondWorld);
            assertEquals(5, holder.data().value);
            assertTrue(holder.updateAndSave(config -> config.value = 23).accepted());
            ConfigRegistry.activateWorld(firstWorld);
            assertEquals(19, holder.data().value);
            assertEquals(List.of(11, 5, 19), loads);
            assertTrue(Files.readString(worldFile(secondWorld)).contains("23"));
            assertFalse(Files.exists(installation));

            ConfigRegistry.deactivateWorld();
            assertFalse(registration.isActive());
            assertEquals(5, holder.data().value);
            String saved = Files.readString(firstFile);
            holder.load();
            holder.save();
            assertFalse(holder.update(config -> config.value = 99).accepted());
            assertFalse(holder.updateAndSave(config -> config.value = 99).accepted());
            assertEquals(5, holder.data().value);
            assertEquals(saved, Files.readString(firstFile));
        } finally {
            ConfigRegistry.release(registration);
        }
    }

    @Test
    void testNewWorldRegistrationsUseCurrentWorldAndIgnoreInstallationBase(@TempDir Path directory) {
        Path world = directory.resolve("world");
        ConfigRegistry.activateWorld(world);
        RegisteredConfig<WorldConfig> first =
            ConfigRegistry.register(settings(WorldConfig.class, directory.resolve("first-installation")));
        RegisteredConfig<WorldConfig> second =
            ConfigRegistry.register(settings(WorldConfig.class, directory.resolve("second-installation")));

        assertSame(first, second);
        assertSame(first.access(), second.access());
        assertSame(first.access(), LiteConfig.holder(WorldConfig.class)
            .modId("mod").baseDir(directory.resolve("third-installation")).create());
        assertTrue(first.isActive());
        assertEquals(ConfigType.WORLD, first.pathResolver().type());
        assertEquals(directory.resolve("first-installation"), first.pathResolver().baseDirectory());
        assertEquals(worldFile(world), first.pathResolver().activePath().orElseThrow());
        assertTrue(Files.exists(worldFile(world)));
        assertFalse(Files.exists(directory.resolve("first-installation")));
        assertFalse(Files.exists(directory.resolve("second-installation")));
    }

    @Test
    void testWorldConfigUsesTheSaveRootSharedByEveryDimension(@TempDir Path directory) throws Exception {
        Path world = directory.resolve("saves").resolve("world");
        Path nether = Files.createDirectories(world.resolve("DIM-1"));
        Path end = Files.createDirectories(world.resolve("DIM1"));
        Path customDimension = Files.createDirectories(world.resolve("dimensions").resolve("example").resolve("moon"));
        RegisteredConfig<WorldConfig> registration =
            ConfigRegistry.register(settings(WorldConfig.class, directory.resolve("installation")));

        ConfigRegistry.activateWorld(world);

        assertEquals(worldFile(world), registration.pathResolver().activePath().orElseThrow());
        assertTrue(Files.exists(worldFile(world)));
        for (Path dimension : List.of(nether, end, customDimension)) {
            assertFalse(Files.exists(dimension.resolve("liteconfig")));
        }
    }

    @Test
    void testEachWorldStartsFromUnmodifiedDeclaredDefaults(@TempDir Path directory) {
        RegisteredConfig<HookedWorldConfig> registration =
            ConfigRegistry.register(settings(HookedWorldConfig.class, directory.resolve("installation")));
        assertEquals(5, registration.state().published().value);

        ConfigRegistry.activateWorld(directory.resolve("first"));
        assertEquals(6, registration.state().published().value);
        ConfigRegistry.deactivateWorld();
        assertEquals(5, registration.state().published().value);
        ConfigRegistry.activateWorld(directory.resolve("second"));
        assertEquals(6, registration.state().published().value);
    }

    @Test
    void testInactiveWorldDoesNotReserveAnInstallationFile(@TempDir Path directory) {
        RegisteredConfig<WorldConfig> world = ConfigRegistry.register(settings(WorldConfig.class, directory));
        assertFalse(world.isActive());
        assertFalse(Files.exists(directory.resolve("mod")));
        assertEquals(5, world.declaredDefaults().value);
        WorldConfig copy = world.declaredDefaults();
        copy.value = 99;
        assertEquals(5, world.declaredDefaults().value);
    }

    @Test
    void testDefectiveWorldLoadPropagatesAndRestoresUntouchedValidatedDefaults(@TempDir Path directory) {
        RegisteredConfig<BrokenWorldHook> registration =
            ConfigRegistry.register(settings(BrokenWorldHook.class, directory.resolve("installation")));
        LiteConfigException failure = assertThrows(LiteConfigException.class,
            () -> ConfigRegistry.activateWorld(directory.resolve("world")));
        assertEquals(ConfigError.EXTENSION_HOOK_FAILED, failure.error());
        assertTrue(failure.defect());
        assertFalse(registration.isActive());
        assertFalse(registration.isLocallyWritable());
        assertTrue(registration.pathResolver().activePath().isEmpty());
        assertEquals(5, registration.state().published().value);
        assertEquals(5, registration.declaredDefaults().value);
    }

    @Test
    void testWorldFileCollisionDeactivatesAndReleasesAllClaims(@TempDir Path directory) {
        RegisteredConfig<WorldConfig> first =
            ConfigRegistry.register(settings(WorldConfig.class, directory.resolve("installation")));
        RegisteredConfig<ConflictingWorldConfig> second =
            ConfigRegistry.register(settings(ConflictingWorldConfig.class, directory.resolve("installation")));
        Path world = directory.resolve("world");

        assertEquals(ConfigError.CONFLICTING_CONFIG_PATH,
            assertThrows(LiteConfigException.class, () -> ConfigRegistry.activateWorld(world)).error());
        assertFalse(first.isActive());
        assertFalse(second.isActive());
        assertEquals(5, first.state().published().value);
        assertEquals(13, second.state().published().value);
        assertTrue(ConfigRegistry.register(settings(InstallationConfig.class, world.resolve("liteconfig"))).isActive());
    }

    @Test
    void testWorldTransitionsDoNotRequestManifestRefresh(@TempDir Path directory) {
        RegisteredConfig<WorldConfig> first =
            ConfigRegistry.register(settings(WorldConfig.class, directory.resolve("installation")));
        RegisteredConfig<HookedWorldConfig> second =
            ConfigRegistry.register(settings(HookedWorldConfig.class, directory.resolve("installation")));
        List<List<Boolean>> contexts = new ArrayList<>();
        TestSyncPlatform.setManifestScheduler(
            () -> contexts.add(List.of(first.isActive(), second.isActive())));
        try {
            ConfigRegistry.activateWorld(directory.resolve("first"));
            assertTrue(first.isActive());
            assertTrue(second.isActive());
            ConfigRegistry.activateWorld(directory.resolve("first"));
            ConfigRegistry.activateWorld(directory.resolve("second"));
            ConfigRegistry.deactivateWorld();
            assertFalse(first.isActive());
            assertFalse(second.isActive());
            assertTrue(contexts.isEmpty(), "world switching must not request a manifest refresh");
        } finally {
            TestSyncPlatform.setManifestScheduler(() -> {});
        }
    }

    @Test
    void testClosingFromWorldLoadKeepsOwnershipUntilTheOperationFinishes(@TempDir Path directory) {
        Path installation = directory.resolve("installation");
        Path world = directory.resolve("world");
        RegisteredConfig<WorldConfig> registration =
            ConfigRegistry.register(settings(WorldConfig.class, installation));
        ConfigHolder<WorldConfig> first =
            LiteConfig.holder(WorldConfig.class).modId("mod").baseDir(installation).create();
        ConfigHolder<WorldConfig> second =
            LiteConfig.holder(WorldConfig.class).modId("mod").baseDir(installation).create();
        List<Integer> loads = new ArrayList<>();
        first.onLoad(loaded -> {
            loads.add(loaded.value);
            second.close();
            assertSame(loaded, assertDoesNotThrow(first::data));
            assertEquals(ConfigError.HOLDER_CLOSED,
                assertThrows(LiteConfigException.class, first::metadata).error());
            assertEquals(ConfigError.CONFLICTING_CONFIG_PATH,
                assertThrows(LiteConfigException.class, () -> ConfigRegistry.register(
                    settings(InstallationConfig.class, world.resolve("liteconfig")))).error());
        });
        second.onLoad(loaded -> loads.add(-loaded.value));

        ConfigRegistry.activateWorld(world);

        assertEquals(List.of(5), loads);
        assertTrue(registration.isClosed());
        assertEquals(5, first.data().value);
        RegisteredConfig<InstallationConfig> replacement =
            ConfigRegistry.register(settings(InstallationConfig.class, world.resolve("liteconfig")));
        assertTrue(replacement.isActive());
        ConfigRegistry.release(replacement);
    }

    private static Path worldFile(Path world) {
        return world.resolve("liteconfig").resolve("mod").resolve("rules").resolve("world.json5");
    }

    private static <T> ConfigSettings<T> settings(Class<T> type, Path installation) {
        return new ConfigSettings<>(type, new ConfigScope("mod"), installation);
    }
}
