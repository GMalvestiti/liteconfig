package com.gmalvestiti.minecraft.liteconfig.holder;

import com.gmalvestiti.minecraft.liteconfig.api.ConfigHolder;
import com.gmalvestiti.minecraft.liteconfig.api.ConfigType;
import com.gmalvestiti.minecraft.liteconfig.api.LiteConfig;
import com.gmalvestiti.minecraft.liteconfig.api.RestartSyncPolicy;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Config;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Entry;
import com.gmalvestiti.minecraft.liteconfig.support.ConfigRegistryIsolation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(ConfigRegistryIsolation.class)
class ServerRestartTest {

    @Test
    void restartOnlyChangesStayPendingWhileOtherChangesBecomeActive(@TempDir Path directory) {
        try (ConfigHolder<ServerPreferences> holder = holder(directory)) {
            assertEquals(ConfigType.SERVER, holder.type());
            assertTrue(holder.update(value -> {
                value.restart = 8;
                value.immediate = 2;
            }).accepted());
            assertEquals(5, holder.data().restart);
            assertEquals(2, holder.data().immediate);
            assertEquals(8, holder.pending().orElseThrow().restart);

            assertTrue(holder.update(value -> value.immediate = 3).accepted());
            assertEquals(5, holder.data().restart);
            assertEquals(3, holder.data().immediate);
            assertEquals(8, holder.pending().orElseThrow().restart);
            assertEquals(3, holder.pending().orElseThrow().immediate);

            holder.save();
            holder.load();
            assertEquals(5, holder.data().restart);
            assertEquals(3, holder.data().immediate);
            assertEquals(8, holder.pending().orElseThrow().restart);
        }
    }

    @Test
    void updateAndSavePersistsPendingValuesForTheNextStartup(@TempDir Path directory)
        throws Exception {
        Path configFile;
        try (ConfigHolder<ServerPreferences> holder = holder(directory)) {
            configFile = holder.path().orElseThrow();
            assertTrue(holder.updateAndSave(value -> value.restart = 9).accepted());
            assertEquals(5, holder.data().restart);
            assertEquals(9, holder.pending().orElseThrow().restart);
            assertTrue(Files.readString(configFile).contains("\"restart\": 9"));
        }

        try (ConfigHolder<ServerPreferences> restarted = holder(directory)) {
            assertEquals(9, restarted.data().restart);
            assertTrue(restarted.pending().isEmpty());
        }
    }

    @Test
    void explicitReloadStagesFileEditsWithoutHotActivation(@TempDir Path directory) throws Exception {
        try (ConfigHolder<ServerPreferences> holder = holder(directory)) {
            Path file = holder.path().orElseThrow();
            Files.writeString(file, "{ restart: 9, immediate: 4 }");

            holder.load();

            assertEquals(5, holder.data().restart);
            assertEquals(4, holder.data().immediate);
            assertEquals(9, holder.pending().orElseThrow().restart);
            holder.save();
            holder.load();
            assertEquals(5, holder.data().restart);
            assertEquals(9, holder.pending().orElseThrow().restart);
        }

        try (ConfigHolder<ServerPreferences> restarted = holder(directory)) {
            assertEquals(9, restarted.data().restart);
            assertTrue(restarted.pending().isEmpty());
        }
    }

    private static ConfigHolder<ServerPreferences> holder(Path directory) {
        return LiteConfig.holder(ServerPreferences.class).modId("mod").baseDir(directory).create();
    }

    @Config(name = "server-preferences", type = ConfigType.SERVER,
        restartSyncPolicy = RestartSyncPolicy.DISCONNECT)
    static class ServerPreferences {
        @Entry(restart = true)
        int restart = 5;
        int immediate = 1;
    }
}
