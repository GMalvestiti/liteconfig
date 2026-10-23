package com.gmalvestiti.minecraft.liteconfig.holder;

import com.gmalvestiti.minecraft.liteconfig.api.ConfigHolder;
import com.gmalvestiti.minecraft.liteconfig.api.ConfigExtension;
import com.gmalvestiti.minecraft.liteconfig.api.ConfigType;
import com.gmalvestiti.minecraft.liteconfig.api.LiteConfig;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Config;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Entry;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Range;
import com.gmalvestiti.minecraft.liteconfig.api.spi.Violation;
import com.gmalvestiti.minecraft.liteconfig.platform.Platform;
import com.gmalvestiti.minecraft.liteconfig.support.ConfigRegistryIsolation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mockStatic;

@ExtendWith(ConfigRegistryIsolation.class)
class ClientRestartTest {
    @Test
    void updatesStageRestartValuesAndPreserveThemAcrossLaterEditsAndSaves(@TempDir Path directory)
        throws Exception {
        try (var platform = mockStatic(Platform.class)) {
            platform.when(Platform::isClient).thenReturn(true);
            try (ConfigHolder<Preferences> holder = holder(directory)) {
                Preferences before = holder.data();
                String original = Files.readString(holder.path().orElseThrow());
                assertTrue(holder.update(value -> {
                    value.restart = 8;
                    value.immediate = 2;
                }).accepted());
                assertEquals(5, holder.data().restart);
                assertEquals(2, holder.data().immediate);
                assertEquals(8, holder.pending().orElseThrow().restart);
                assertEquals(original, Files.readString(holder.path().orElseThrow()));
                holder.pending().orElseThrow().restart = 99;
                assertEquals(8, holder.pending().orElseThrow().restart);
                holder.load();
                assertEquals(5, holder.data().restart);
                assertEquals(8, holder.pending().orElseThrow().restart);
                assertTrue(holder.update(value -> value.immediate = 3).accepted());
                assertEquals(8, holder.pending().orElseThrow().restart);
                assertEquals(3, holder.pending().orElseThrow().immediate);
                holder.save();
                assertTrue(Files.readString(holder.path().orElseThrow()).contains("\"restart\": 8"));
                holder.load();
                assertEquals(5, holder.data().restart);
                assertEquals(8, holder.pending().orElseThrow().restart);
                assertEquals(5, before.restart);
                assertEquals(1, before.immediate);
            }
            try (ConfigHolder<Preferences> restarted = holder(directory)) {
                assertEquals(8, restarted.data().restart);
                assertEquals(3, restarted.data().immediate);
                assertTrue(restarted.pending().isEmpty());
            }
        }
    }

    @Test
    void updateAndSavePersistsDesiredValuesButEventsSeeActiveValues(@TempDir Path directory)
        throws Exception {
        try (var platform = mockStatic(Platform.class)) {
            platform.when(Platform::isClient).thenReturn(true);
            try (ConfigHolder<Preferences> holder = holder(directory)) {
                holder.onUpdate(value -> assertEquals(5, value.restart));
                holder.onSave(value -> assertEquals(5, value.restart));
                assertTrue(holder.updateAndSave(value -> value.restart = 9).accepted());
                assertEquals(5, holder.copy().restart);
                assertEquals(9, holder.pending().orElseThrow().restart);
                assertTrue(Files.readString(holder.path().orElseThrow()).contains("\"restart\": 9"));
                assertTrue(holder.updateAndSave(value -> value.restart = 5).accepted());
                assertTrue(holder.pending().isEmpty());
                assertTrue(Files.readString(holder.path().orElseThrow()).contains("\"restart\": 5"));
            }
        }
    }

    @Test
    void invalidOrFailedWritesDoNotReplaceActiveOrPendingState(@TempDir Path directory)
        throws Exception {
        try (var platform = mockStatic(Platform.class)) {
            platform.when(Platform::isClient).thenReturn(true);
            try (ConfigHolder<Preferences> holder = holder(directory)) {
                assertTrue(holder.update(value -> value.restart = 8).accepted());
                assertFalse(holder.update(value -> value.restart = -1).accepted());
                assertEquals(8, holder.pending().orElseThrow().restart);
                Path file = holder.path().orElseThrow();
                Files.delete(file);
                Files.createDirectory(file);
                Files.writeString(file.resolve("blocked"), "block replacement");
                assertFalse(holder.updateAndSave(value -> {
                    value.restart = 9;
                    value.immediate = 2;
                }).accepted());
                assertEquals(5, holder.data().restart);
                assertEquals(1, holder.data().immediate);
                assertEquals(8, holder.pending().orElseThrow().restart);
            }
        }
    }

    @Test
    void loadingAnEditedFileStagesRatherThanActivatesRestartValues(@TempDir Path directory)
        throws Exception {
        try (var platform = mockStatic(Platform.class)) {
            platform.when(Platform::isClient).thenReturn(true);
            try (ConfigHolder<Preferences> holder = holder(directory)) {
                Files.writeString(holder.path().orElseThrow(), "{ restart: 12, immediate: 4 }");
                holder.load();
                assertEquals(5, holder.data().restart);
                assertEquals(4, holder.data().immediate);
                assertEquals(12, holder.pending().orElseThrow().restart);
            }
        }
    }

    private static ConfigHolder<Preferences> holder(Path directory) {
        return LiteConfig.holder(Preferences.class).modId("mod").baseDir(directory).create();
    }

    @Test
    void desiredValuesCannotPublishAnInvalidActiveCombination(@TempDir Path directory) {
        try (var platform = mockStatic(Platform.class)) {
            platform.when(Platform::isClient).thenReturn(true);
            try (ConfigHolder<LinkedPreferences> holder = LiteConfig.holder(LinkedPreferences.class)
                .modId("mod").baseDir(directory).create()) {
                assertFalse(holder.update(value -> {
                    value.capacity = 10;
                    value.usage = 8;
                }).accepted());
                assertEquals(5, holder.data().capacity);
                assertEquals(1, holder.data().usage);
                assertTrue(holder.pending().isEmpty());
                assertTrue(holder.update(value -> value.capacity = 10).accepted());
                assertFalse(holder.update(value -> value.usage = 8).accepted());
                assertEquals(10, holder.pending().orElseThrow().capacity);
                assertEquals(1, holder.pending().orElseThrow().usage);
            }
        }
    }

    @Config(name = "linked-preferences", type = ConfigType.CLIENT)
    static class LinkedPreferences implements ConfigExtension {
        @Entry(restart = true)
        int capacity = 5;
        int usage = 1;

        @Override
        public void validate(List<Violation> violations) {
            if (usage > capacity) {
                violations.add(Violation.of("capacity", "usage must not exceed capacity"));
            }
        }
    }

    @Config(name = "preferences", type = ConfigType.CLIENT)
    static class Preferences {
        @Entry(restart = true)
        @Range(min = 1)
        int restart = 5;
        int immediate = 1;
    }
}
