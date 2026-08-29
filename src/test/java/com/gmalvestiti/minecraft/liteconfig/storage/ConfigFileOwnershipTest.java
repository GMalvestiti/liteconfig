package com.gmalvestiti.minecraft.liteconfig.storage;

import com.gmalvestiti.minecraft.liteconfig.exception.ConfigError;
import com.gmalvestiti.minecraft.liteconfig.exception.LiteConfigException;
import com.gmalvestiti.minecraft.liteconfig.support.TestFixtures;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConfigFileOwnershipTest {

    @Test
    void testRejectsCaseInsensitivePathConflicts(@TempDir Path tempDir) {
        ConfigFileOwnership ownership = new ConfigFileOwnership();
        ownership.claim(String.class, tempDir.resolve("Example.json5"), TestFixtures.SCOPE);

        LiteConfigException failure = assertThrows(
            LiteConfigException.class,
            () -> ownership.claim(Integer.class, tempDir.resolve("example.JSON5"), TestFixtures.SCOPE)
        );

        assertEquals(ConfigError.CONFLICTING_CONFIG_PATH, failure.error());
    }
}
