package com.gmalvestiti.minecraft.liteconfig.network;

import com.gmalvestiti.minecraft.liteconfig.api.ConfigHolder;
import com.gmalvestiti.minecraft.liteconfig.api.ConfigType;
import com.gmalvestiti.minecraft.liteconfig.api.LiteConfig;
import com.gmalvestiti.minecraft.liteconfig.api.SyncMode;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Config;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Entry;
import com.gmalvestiti.minecraft.liteconfig.context.ConfigModel;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigError;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigScope;
import com.gmalvestiti.minecraft.liteconfig.exception.LiteConfigException;
import com.gmalvestiti.minecraft.liteconfig.metadata.DeclaredConfig;
import com.gmalvestiti.minecraft.liteconfig.support.ConfigRegistryIsolation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(ConfigRegistryIsolation.class)
class ConfigRootSyncModeTest {

    @Test
    void rootDefaultsToDisabledWhileEntriesContinueToInherit() throws NoSuchMethodException {
        assertEquals(SyncMode.class, Config.class.getDeclaredMethod("sync").getReturnType());
        assertEquals(SyncMode.DISABLED, DefaultRoot.class.getAnnotation(Config.class).sync());
        assertEquals(SyncMode.INHERIT, Entry.class.getDeclaredMethod("sync").getDefaultValue());
        assertFalse(DeclaredConfig.of(DefaultRoot.class).sync());
        assertTrue(ConfigModel.of(DefaultRoot.class, new ConfigScope("mod")).metadata().synced().isEmpty());
    }

    @Test
    void disabledRootStillAllowsFieldOptIn() {
        ConfigModel<DisabledRoot> model = ConfigModel.of(DisabledRoot.class, new ConfigScope("mod"));

        assertFalse(DeclaredConfig.of(DisabledRoot.class).sync());
        assertEquals(List.of("selected"), model.metadata().synced().stream().map(property -> property.path()).toList());
        assertFalse(model.metadata().property("inherited").orElseThrow().sync());
        assertFalse(model.metadata().property("excluded.selected").orElseThrow().sync());
    }

    @Test
    void inheritedRootIsRejectedDuringModelCreation() {
        LiteConfigException failure = assertThrows(LiteConfigException.class,
            () -> ConfigModel.of(InheritedRoot.class, new ConfigScope("mod")));

        assertEquals(ConfigError.INVALID_CONFIG_SYNC, failure.error());
        assertTrue(failure.error().defect());
        assertTrue(failure.getMessage().contains("ENABLED or DISABLED"));
        assertTrue(failure.getMessage().contains("no parent"));
    }

    @Test
    void inheritedRootIsRejectedBeforeCreatingStorageDirectoriesOrFiles(@TempDir Path directory) {
        Path base = directory.resolve("not-created");

        LiteConfigException failure = assertThrows(LiteConfigException.class,
            () -> LiteConfig.holder(InheritedRoot.class).modId("mod").baseDir(base).create());

        assertEquals(ConfigError.INVALID_CONFIG_SYNC, failure.error());
        assertFalse(Files.exists(base));
    }

    @Test
    void clientRootRemainsLocalEvenWhenRootAndFieldSyncAreEnabled(@TempDir Path directory) {

        try (ConfigHolder<ClientRoot> holder = LiteConfig.holder(ClientRoot.class)
            .modId("mod").baseDir(directory).create()) {
            assertEquals(7, holder.data().value);
            assertTrue(ConfigSyncRegistry.values().isEmpty());
            assertTrue(ServerConfigSync.beginHandshake(true).isEmpty());
        }
    }

    @Config(name = "default-root")
    static class DefaultRoot {
        public int value = 1;
    }

    @Config(name = "disabled-root", sync = SyncMode.DISABLED)
    static class DisabledRoot {
        public int inherited = 1;
        @Entry(sync = SyncMode.ENABLED)
        public int selected = 2;
        @Entry(sync = SyncMode.DISABLED)
        public Section excluded = new Section();
    }

    static class Section {
        @Entry(sync = SyncMode.ENABLED)
        public int selected = 3;
    }

    @Config(name = "inherited-root", sync = SyncMode.INHERIT)
    static class InheritedRoot {
        public int value = 1;
    }

    @Config(name = "client-root", type = ConfigType.CLIENT, sync = SyncMode.ENABLED)
    static class ClientRoot {
        @Entry(sync = SyncMode.ENABLED)
        public int value = 7;
    }
}
