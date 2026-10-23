package com.gmalvestiti.minecraft.liteconfig.network;

import com.gmalvestiti.minecraft.liteconfig.api.ConfigHolder;
import com.gmalvestiti.minecraft.liteconfig.api.ConfigType;
import com.gmalvestiti.minecraft.liteconfig.api.LiteConfig;
import com.gmalvestiti.minecraft.liteconfig.api.SyncMode;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Config;
import com.gmalvestiti.minecraft.liteconfig.registry.ConfigRegistry;
import com.gmalvestiti.minecraft.liteconfig.support.ConfigRegistryIsolation;
import com.gmalvestiti.minecraft.liteconfig.support.TestSyncPlatform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(ConfigRegistryIsolation.class)
class ConfigWorldSyncLifecycleTest {

    @Test
    void runtimeBindingRefreshesManifestAfterWorldActivation(@TempDir Path directory) throws Exception {
        ConfigRegistryIsolation.beforeGameStartup();
        List<Integer> manifestSizes = new ArrayList<>();
        TestSyncPlatform.setManifestScheduler(() -> manifestSizes.add(
            ServerConfigSync.beginHandshake().stream().mapToInt(packet -> packet.hashes().size()).sum()));

        try (ConfigHolder<FirstWorldConfig> first = LiteConfig.holder(FirstWorldConfig.class)
            .modId("mod").baseDir(directory).create();
             ConfigHolder<SecondWorldConfig> second = LiteConfig.holder(SecondWorldConfig.class)
                 .modId("mod").baseDir(directory).create()) {
            ConfigRegistry.activateWorld(directory.resolve("world"));
            assertTrue(first.isActive());
            assertTrue(second.isActive());
            assertTrue(manifestSizes.isEmpty());

            TestSyncPlatform.setServerMainThreadExecutor(Runnable::run);
            assertEquals(List.of(2), manifestSizes);

            TestSyncPlatform.clearServerMainThreadExecutor(TestSyncPlatform.serverExecutor());
            ConfigRegistry.deactivateWorld();
            assertEquals(List.of(2), manifestSizes);
            assertTrue(ServerConfigSync.beginHandshake().isEmpty());
        }
    }

    @Config(name = "first-world-sync", type = ConfigType.WORLD, sync = SyncMode.ENABLED)
    static class FirstWorldConfig {
        public int value = 1;
    }

    @Config(name = "second-world-sync", type = ConfigType.WORLD, sync = SyncMode.ENABLED)
    static class SecondWorldConfig {
        public int value = 2;
    }
}
