package com.gmalvestiti.minecraft.liteconfig.validation;

import com.gmalvestiti.minecraft.liteconfig.api.ConfigHolder;
import com.gmalvestiti.minecraft.liteconfig.api.LiteConfig;
import com.gmalvestiti.minecraft.liteconfig.api.UpdateResult;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Config;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Entry;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigError;
import com.gmalvestiti.minecraft.liteconfig.exception.LiteConfigException;
import com.gmalvestiti.minecraft.liteconfig.support.TestFixtures;
import com.gmalvestiti.minecraft.liteconfig.support.ConfigRegistryIsolation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(ConfigRegistryIsolation.class)
class ConfigGuardRestartTest {

    @Test
    void testRejectsAnUpdateThatTouchesARestartOnlyField(@TempDir Path tempDir) {
        ConfigHolder<TestFixtures.EntryConfig> holder = holder(tempDir);

        UpdateResult result = holder.update(config -> config.worldPreset = "flat");

        assertFalse(result.accepted());
        assertEquals("restart.worldPreset", result.violations().getFirst().id());
        assertEquals("default", holder.data().worldPreset, "the rejected candidate must not reach the state");
    }

    @Test
    void testRejectsARestartOnlyFieldNestedInsideTheModel(@TempDir Path tempDir) {
        ConfigHolder<TestFixtures.EntryConfig> holder = holder(tempDir);

        UpdateResult result = holder.update(config -> config.section.experimental = true);

        assertFalse(result.accepted());
        assertEquals("restart.experimental", result.violations().getFirst().id());
    }

    @Test
    void testLeavesTheRestOfTheUpdateAlone(@TempDir Path tempDir) {
        ConfigHolder<TestFixtures.EntryConfig> holder = holder(tempDir);

        assertTrue(holder.update(config -> config.hudScale = 6).accepted());
        assertEquals(6, holder.data().hudScale);
    }

    @Test
    void testStillAcceptsTheValueTheFileSupplies(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("entries.json5"), "{\"worldPreset\": \"flat\"}");

        ConfigHolder<TestFixtures.EntryConfig> holder = holder(tempDir);

        assertEquals("flat", holder.data().worldPreset,
            "a restart-only field is edited in the file, so loading it is the supported path");
    }

    @Test
    void testReportsTheDedicatedErrorUnderAStrictPolicy(@TempDir Path tempDir) {
        ConfigHolder<TestFixtures.StrictEntryConfig> holder =
            LiteConfig.holder(TestFixtures.StrictEntryConfig.class)
                .modId("mod")
                .baseDir(tempDir.toString())
                .create();

        LiteConfigException failure = org.junit.jupiter.api.Assertions.assertThrows(
            LiteConfigException.class,
            () -> holder.update(config -> config.worldPreset = "flat")
        );
        assertEquals(ConfigError.RESTART_FIELD_CHANGED, failure.error());
        assertEquals(1, failure.violations().size());
    }

    @Test
    void testRestartTraversalStopsAtObjectCycles() {
        ConfigGuard<CyclicConfig> guard = new ConfigGuard<>(TestFixtures.model(CyclicConfig.class));
        CyclicConfig current = new CyclicConfig();
        CyclicConfig candidate = new CyclicConfig();

        guard.enforceRestart(current, candidate);

        candidate.node.value = 2;
        LiteConfigException failure = org.junit.jupiter.api.Assertions.assertThrows(
            LiteConfigException.class,
            () -> guard.enforceRestart(current, candidate));
        assertEquals(ConfigError.RESTART_FIELD_CHANGED, failure.error());
    }

    private static ConfigHolder<TestFixtures.EntryConfig> holder(Path tempDir) {
        return LiteConfig.holder(TestFixtures.EntryConfig.class)
            .modId("mod")
            .baseDir(tempDir.toString())
            .create();
    }

    @Config(name = "cyclic")
    static class CyclicConfig {
        CyclicNode node = new CyclicNode();

        CyclicConfig() {
            node.next = node;
        }
    }

    static class CyclicNode {
        @Entry(restart = true)
        int value = 1;
        CyclicNode next;
    }
}
