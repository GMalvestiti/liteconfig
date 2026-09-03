package com.gmalvestiti.minecraft.liteconfig.network;

import com.gmalvestiti.minecraft.liteconfig.api.ConfigHolder;
import com.gmalvestiti.minecraft.liteconfig.api.LiteConfig;
import com.gmalvestiti.minecraft.liteconfig.support.ConfigRegistryIsolation;
import com.gmalvestiti.minecraft.liteconfig.support.TestFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(ConfigRegistryIsolation.class)
class ConfigSyncInitializationTest {

    @Test
    void testInitializationRegistersExistingAndFutureHolders(@TempDir Path tempDir) {
        ConfigHolder<TestFixtures.SyncedConfig> early = holder(tempDir.resolve("early"));

        assertTrue(ConfigSyncRegistry.beginHandshake().isEmpty());

        ConfigSyncRegistry.initialize();

        assertFalse(ConfigSyncRegistry.beginHandshake().isEmpty());

        early.close();
        assertTrue(ConfigSyncRegistry.beginHandshake().isEmpty());

        ConfigHolder<TestFixtures.SyncedConfig> late = holder(tempDir.resolve("late"));
        assertFalse(ConfigSyncRegistry.beginHandshake().isEmpty());

        late.close();

        assertTrue(ConfigSyncRegistry.beginHandshake().isEmpty());
    }

    private static ConfigHolder<TestFixtures.SyncedConfig> holder(Path baseDir) {
        return LiteConfig.holder(TestFixtures.SyncedConfig.class)
            .modId("mod")
            .baseDir(baseDir)
            .create();
    }
}
