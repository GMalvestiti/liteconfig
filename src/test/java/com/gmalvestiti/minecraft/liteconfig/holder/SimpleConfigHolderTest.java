package com.gmalvestiti.minecraft.liteconfig.holder;

import com.gmalvestiti.minecraft.liteconfig.api.LiteConfig;
import com.gmalvestiti.minecraft.liteconfig.api.ConfigHolder;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigError;
import com.gmalvestiti.minecraft.liteconfig.exception.LiteConfigException;
import com.gmalvestiti.minecraft.liteconfig.support.TestFixtures;
import com.gmalvestiti.minecraft.liteconfig.support.ConfigRegistryIsolation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(ConfigRegistryIsolation.class)
class SimpleConfigHolderTest {

    @Test
    void testSupportsTheFullLifecycleInline(@TempDir Path tempDir) {
        ConfigHolder<TestFixtures.ConfigWithExtension> holder = holder(tempDir);

        assertEquals(1, holder.data().value);

        holder.update(cfg -> cfg.value = 4);
        assertEquals(4, holder.data().value);

        holder.updateAndSave(cfg -> cfg.value = 6);
        holder.update(cfg -> cfg.value = 8);
        holder.load();

        assertEquals(6, holder.data().value);
        assertTrue(holder.data().afterLoadCalled);
    }

    @Test
    void testHandsOutAnIsolatedCopyThatCannotReachPublishedState(@TempDir Path tempDir) {
        ConfigHolder<TestFixtures.ConfigWithExtension> holder = holder(tempDir);

        TestFixtures.ConfigWithExtension copy = holder.copy();
        copy.value = 99;

        assertNotSame(holder.data(), copy);
        assertEquals(1, holder.data().value, "mutating a copy must not publish");

        holder.update(cfg -> cfg.value = 5);
        assertEquals(99, copy.value, "an existing copy must not track later updates");
    }

    @Test
    void testRejectsBlockingCallsFromInsideAMutator(@TempDir Path tempDir) {
        ConfigHolder<TestFixtures.ConfigWithExtension> holder = holder(tempDir);

        LiteConfigException failure = assertThrows(LiteConfigException.class, () ->
            holder.update(cfg -> {
                cfg.value = 2;
                holder.save();
            }));

        assertEquals(ConfigError.NESTED_CONFIG_OPERATION, failure.error());
        assertEquals(1, holder.data().value);
    }

    @Test
    void testStillEnforcesValidationOnUpdates(@TempDir Path tempDir) {
        ConfigHolder<TestFixtures.StrictUpdateConfigWithExtension> holder = strictUpdateHolder(tempDir);

        LiteConfigException failure = assertThrows(
            LiteConfigException.class,
            () -> holder.update(cfg -> cfg.value = -1)
        );

        assertEquals(ConfigError.VALIDATION_FAILED, failure.error());
        assertEquals(1, holder.data().value);
    }

    @Test
    void testFallsBackAndBacksUpMalformedDataOnLoad(@TempDir Path tempDir) throws Exception {
        ConfigHolder<TestFixtures.ConfigWithExtension> holder = holder(tempDir);
        Path file = tempDir.resolve("with-extension.json5");
        Files.writeString(file, "{");

        holder.load();

        assertEquals(1, holder.data().value);
        assertFalse(Files.exists(file), "a degraded load moves the file aside and keeps defaults in memory");
        try (var entries = Files.list(tempDir)) {
            assertEquals(1, entries.filter(p -> p.getFileName().toString().contains(".corrupt-")).count());
        }
    }

    @Test
    void testPreservesNewerVersionBeforeSavingDefaults(@TempDir Path tempDir) throws Exception {
        String original = "{\"configVersion\":9,\"hud\":{\"scale\":7},\"profile\":\"future\"}";
        Path file = tempDir.resolve("versioned.json5");
        Files.writeString(file, original);

        ConfigHolder<TestFixtures.VersionedConfig> holder =
            LiteConfig.holder(TestFixtures.VersionedConfig.class)
                .modId("mod")
                .baseDir(tempDir.toString())
                .create();

        assertEquals(2, holder.data().hud.scale);
        assertTrue(Files.readString(file).contains("configVersion"));
        try (var entries = Files.list(tempDir)) {
            Path backup = entries
                .filter(path -> path.getFileName().toString().contains(".corrupt-"))
                .findFirst()
                .orElseThrow();
            assertEquals(original, Files.readString(backup));
        }
    }

    @Test
    void testThrowsOnMalformedDataUnderStrictReadPolicy(@TempDir Path tempDir) throws Exception {
        ConfigHolder<TestFixtures.StrictReadConfigWithExtension> holder = strictReadHolder(tempDir);
        Path file = tempDir.resolve("strict-read-with-extension.json5");
        Files.writeString(file, "{");

        LiteConfigException failure = assertThrows(LiteConfigException.class, holder::load);

        assertEquals(ConfigError.MALFORMED_CONFIG_DATA, failure.error());
        assertTrue(Files.exists(file));
        try (var entries = Files.list(tempDir)) {
            assertEquals(0, entries.filter(p -> p.getFileName().toString().contains(".corrupt-")).count());
        }
    }

    private static ConfigHolder<TestFixtures.ConfigWithExtension> holder(Path tempDir) {
        return LiteConfig.holder(TestFixtures.ConfigWithExtension.class)
            .modId("mod")
            .baseDir(tempDir.toString())
            .create();
    }

    private static ConfigHolder<TestFixtures.StrictReadConfigWithExtension> strictReadHolder(Path tempDir) {
        return LiteConfig.holder(TestFixtures.StrictReadConfigWithExtension.class)
            .modId("mod")
            .baseDir(tempDir.toString())
            .create();
    }

    private static ConfigHolder<TestFixtures.StrictUpdateConfigWithExtension> strictUpdateHolder(Path tempDir) {
        return LiteConfig.holder(TestFixtures.StrictUpdateConfigWithExtension.class)
            .modId("mod")
            .baseDir(tempDir.toString())
            .create();
    }
}
