package com.gmalvestiti.minecraft.liteconfig.storage;

import com.gmalvestiti.minecraft.liteconfig.exception.ConfigError;
import com.gmalvestiti.minecraft.liteconfig.exception.LiteConfigException;
import com.gmalvestiti.minecraft.liteconfig.support.TestFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigStorageTest {

    @Test
    void testReadsMissingAsNull(@TempDir Path tempDir) {
        ConfigStorage<TestFixtures.SimpleConfig> storage = createStorage(tempDir, TestFixtures.SimpleConfig.class);
        TestFixtures.SimpleConfig value = storage.read();
        assertNull(value);
    }

    @Test
    void testThrowsOnIoReadFailure(@TempDir Path tempDir) throws IOException {
        ConfigStorage<TestFixtures.SimpleConfig> storage = createStorage(tempDir, TestFixtures.SimpleConfig.class);
        Path configPath = new ConfigPathResolver(tempDir, TestFixtures.SCOPE, new ConfigFileOwnership()).resolveForConfig(TestFixtures.SimpleConfig.class);
        Files.createDirectories(configPath);

        LiteConfigException ex = assertThrows(
            LiteConfigException.class,
            storage::read
        );
        assertEquals(ConfigError.IO_LOAD_FAILURE, ex.error());
    }

    @Test
    void testTreatsAnEmptyFileAsMalformedRatherThanAbsent(@TempDir Path tempDir) throws IOException {
        ConfigStorage<TestFixtures.SimpleConfig> storage = createStorage(tempDir, TestFixtures.SimpleConfig.class);
        Path configPath = new ConfigPathResolver(tempDir, TestFixtures.SCOPE, new ConfigFileOwnership()).resolveForConfig(TestFixtures.SimpleConfig.class);
        Files.createDirectories(configPath.getParent());

        for (String content : new String[] {"", "   ", "null"}) {
            Files.writeString(configPath, content);
            LiteConfigException ex = assertThrows(
                LiteConfigException.class,
                storage::read
            );
            assertEquals(ConfigError.MALFORMED_CONFIG_DATA, ex.error());
        }
    }

    @Test
    void testWritesAndReadsConfig(@TempDir Path tempDir) {
        ConfigStorage<TestFixtures.SimpleConfig> storage = createStorage(tempDir, TestFixtures.SimpleConfig.class);
        TestFixtures.SimpleConfig config = new TestFixtures.SimpleConfig();
        config.value = 12;
        storage.write(config);
        TestFixtures.SimpleConfig read = storage.read();
        assertNotNull(read);
        assertEquals(12, read.value);
    }

    @Test
    void testThrowsMalformedOnParseFailureAndBacksUpOnDemand(@TempDir Path tempDir) throws IOException {
        ConfigStorage<TestFixtures.SimpleConfig> storage = createStorage(tempDir, TestFixtures.SimpleConfig.class);

        Path configPath = new ConfigPathResolver(tempDir, TestFixtures.SCOPE, new ConfigFileOwnership()).resolveForConfig(TestFixtures.SimpleConfig.class);
        Files.createDirectories(configPath.getParent());
        Files.writeString(configPath, "{ this is not a config");

        LiteConfigException ex = assertThrows(
            LiteConfigException.class,
            storage::read
        );
        assertEquals(ConfigError.MALFORMED_CONFIG_DATA, ex.error());

        storage.backupCorrupted();
        assertFalse(Files.exists(configPath));
        try (var entries = Files.list(configPath.getParent())) {
            long backups = entries.filter(p -> p.getFileName().toString().contains(".corrupt-")).count();
            assertEquals(1, backups);
        }
    }

    @Test
    void testSkipsTheBackupWhenTheFileIsNotOnDisk(@TempDir Path tempDir) throws IOException {
        ConfigStorage<TestFixtures.SimpleConfig> storage = createStorage(tempDir, TestFixtures.SimpleConfig.class);

        assertDoesNotThrow(storage::backupCorrupted);
        assertNoBackupsIn(tempDir);
    }

    @Test
    void testWrapsWriteFailures(@TempDir Path tempDir) {
        ConfigStorage<TestFixtures.UnserializableConfig> storage =
            createStorage(tempDir, TestFixtures.UnserializableConfig.class);

        LiteConfigException ex = assertThrows(
            LiteConfigException.class,
            () -> storage.write(new TestFixtures.UnserializableConfig())
        );
        assertEquals(ConfigError.IO_SAVE_FAILURE, ex.error());
    }

    @Test
    void testLeavesNoTempFileWhenACommitFails(@TempDir Path tempDir) throws IOException {
        ConfigStorage<TestFixtures.SimpleConfig> storage = createStorage(tempDir, TestFixtures.SimpleConfig.class);
        Path configPath = new ConfigPathResolver(tempDir, TestFixtures.SCOPE, new ConfigFileOwnership())
            .resolveForConfig(TestFixtures.SimpleConfig.class);
        Files.createDirectories(configPath);
        Files.writeString(configPath.resolve("child.txt"), "x");

        assertThrows(
            LiteConfigException.class,
            () -> storage.write(new TestFixtures.SimpleConfig())
        );

        assertTrue(Files.exists(configPath), "a failed commit must not disturb the existing target");
        assertNoTempFilesIn(configPath.getParent());
    }

    private static void assertNoTempFilesIn(Path directory) throws IOException {
        try (var entries = Files.list(directory)) {
            assertEquals(
                0,
                entries.filter(p -> p.getFileName().toString().contains(".tmp")).count(),
                "a discarded stage must leave nothing behind");
        }
    }

    private static void assertNoBackupsIn(Path directory) throws IOException {
        try (var entries = Files.walk(directory)) {
            assertEquals(
                0,
                entries.filter(p -> p.getFileName().toString().contains(".corrupt-")).count(),
                "a skipped backup must leave nothing behind");
        }
    }

    private static <T> ConfigStorage<T> createStorage(Path tempDir, Class<T> configType) {
        return TestFixtures.storage(tempDir, configType);
    }
}
