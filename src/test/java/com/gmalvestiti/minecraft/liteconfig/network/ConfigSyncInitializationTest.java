package com.gmalvestiti.minecraft.liteconfig.network;

import com.gmalvestiti.minecraft.liteconfig.api.ConfigHolder;
import com.gmalvestiti.minecraft.liteconfig.api.ConfigType;
import com.gmalvestiti.minecraft.liteconfig.api.LiteConfig;
import com.gmalvestiti.minecraft.liteconfig.api.SyncMode;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Config;
import com.gmalvestiti.minecraft.liteconfig.registry.ConfigRegistry;
import com.gmalvestiti.minecraft.liteconfig.support.ConfigRegistryIsolation;
import com.gmalvestiti.minecraft.liteconfig.support.TestFixtures;
import com.gmalvestiti.minecraft.liteconfig.support.TestSyncPlatform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(ConfigRegistryIsolation.class)
class ConfigSyncInitializationTest {

    @Test
    void startupActivatesExistingAndFutureHolders(@TempDir Path tempDir) throws Exception {
        ConfigRegistryIsolation.beforeGameStartup();
        ConfigHolder<TestFixtures.SyncedConfig> early = holder(tempDir.resolve("early"));

        assertTrue(ServerConfigSync.beginHandshake(true).isEmpty());

        TestSyncPlatform.reset();

        assertFalse(ServerConfigSync.beginHandshake(true).isEmpty());

        early.close();
        assertTrue(ServerConfigSync.beginHandshake(true).isEmpty());

        ConfigHolder<TestFixtures.SyncedConfig> late = holder(tempDir.resolve("late"));
        assertFalse(ServerConfigSync.beginHandshake(true).isEmpty());

        late.close();

        assertTrue(ServerConfigSync.beginHandshake(true).isEmpty());
    }

    @Test
    void closingBeforeStartupRemovesPendingSyncRegistration(@TempDir Path tempDir) throws Exception {
        ConfigRegistryIsolation.beforeGameStartup();
        ConfigHolder<TestFixtures.SyncedConfig> early = holder(tempDir);

        assertEquals(1, ConfigSyncRegistry.values().size());
        early.close();
        assertTrue(ConfigSyncRegistry.values().isEmpty());

        TestSyncPlatform.reset();

        assertTrue(ServerConfigSync.beginHandshake(true).isEmpty());
        ConfigHolder<TestFixtures.SyncedConfig> replacement = holder(tempDir);
        assertFalse(ServerConfigSync.beginHandshake(true).isEmpty());
        replacement.close();
        assertTrue(ConfigSyncRegistry.values().isEmpty());
    }

    @Test
    void initializingExistingClientAndInactiveWorldHoldersDoesNotAdvertiseThem(@TempDir Path tempDir) {
        ConfigHolder<ClientConfig> client = LiteConfig.holder(ClientConfig.class)
            .modId("mod").baseDir(tempDir).create();
        ConfigHolder<WorldConfig> world = LiteConfig.holder(WorldConfig.class)
            .modId("mod").baseDir(tempDir).create();

        assertTrue(ServerConfigSync.beginHandshake(true).isEmpty());
        assertTrue(world.path().isEmpty());
        assertFalse(world.isActive());
        ConfigRegistry.activateWorld(tempDir.resolve("world"));
        assertTrue(world.isActive());
        assertEquals(java.util.Set.of("mod:" + WorldConfig.class.getName()),
            ServerConfigSync.beginHandshake(true).getFirst().hashes().keySet());
        ConfigRegistry.deactivateWorld();
        assertTrue(ServerConfigSync.beginHandshake(true).isEmpty());
        client.close();
        world.close();
    }

    private static ConfigHolder<TestFixtures.SyncedConfig> holder(Path baseDir) {
        return LiteConfig.holder(TestFixtures.SyncedConfig.class)
            .modId("mod")
            .baseDir(baseDir)
            .create();
    }

    @Config(name = "early-client", type = ConfigType.CLIENT, sync = SyncMode.ENABLED)
    public static class ClientConfig {
        public boolean enabled = true;
    }

    @Config(name = "early-world", type = ConfigType.WORLD, sync = SyncMode.ENABLED)
    public static class WorldConfig {
        public boolean enabled = true;
    }
}
