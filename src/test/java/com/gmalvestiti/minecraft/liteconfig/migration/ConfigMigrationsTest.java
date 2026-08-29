package com.gmalvestiti.minecraft.liteconfig.migration;

import com.gmalvestiti.minecraft.liteconfig.exception.ConfigError;
import com.gmalvestiti.minecraft.liteconfig.exception.LiteConfigException;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Config;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Migration;
import com.gmalvestiti.minecraft.liteconfig.api.migration.ConfigData;
import com.gmalvestiti.minecraft.liteconfig.storage.ConfigStorage;
import com.gmalvestiti.minecraft.liteconfig.support.TestFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigMigrationsTest {

    @Config(name = "library-failure-migration", version = 2)
    static class LibraryFailureMigrationConfig {
        @Migration(from = 1)
        static void toVersion2(ConfigData data) {
            throw TestFixtures.SCOPE.exception(
                ConfigError.IO_LOAD_FAILURE, new IllegalStateException("root"), "old.json5");
        }
    }

    @Test
    void testRunsEveryStepBetweenTheStoredAndDeclaredVersion(@TempDir Path tempDir) throws IOException {
        write(tempDir, "versioned.json5", "{\"hudScale\":7}");

        TestFixtures.VersionedConfig loaded =
            TestFixtures.storage(tempDir, TestFixtures.VersionedConfig.class).read();

        assertEquals(7, loaded.hud.scale);
        assertEquals("default", loaded.profile);
    }

    @Test
    void testStartsFromTheStoredVersionWhenTheFileDeclaresOne(@TempDir Path tempDir) throws IOException {
        write(tempDir, "versioned.json5", "{\"configVersion\":2,\"hud\":{\"scale\":4}}");

        TestFixtures.VersionedConfig loaded =
            TestFixtures.storage(tempDir, TestFixtures.VersionedConfig.class).read();

        assertEquals(4, loaded.hud.scale);
    }

    @Test
    void testLeavesAnUpToDateFileAlone(@TempDir Path tempDir) throws IOException {
        write(tempDir, "versioned.json5", "{\"configVersion\":3,\"hud\":{\"scale\":5},\"profile\":\"steve\"}");

        TestFixtures.VersionedConfig loaded =
            TestFixtures.storage(tempDir, TestFixtures.VersionedConfig.class).read();

        assertEquals(5, loaded.hud.scale);
        assertEquals("steve", loaded.profile);
    }

    @Test
    void testStampsTheDeclaredVersionAheadOfTheOtherKeys(@TempDir Path tempDir) throws IOException {
        ConfigStorage<TestFixtures.VersionedConfig> storage =
            TestFixtures.storage(tempDir, TestFixtures.VersionedConfig.class);

        storage.write(new TestFixtures.VersionedConfig());
        String written = Files.readString(tempDir.resolve("versioned.json5"));

        assertTrue(written.contains("configVersion"));
        assertTrue(
            written.indexOf("configVersion") < written.indexOf("profile"),
            "the version must be the first thing a reader sees");
    }

    @Test
    void testStampsTomlFilesToo(@TempDir Path tempDir) throws IOException {
        ConfigStorage<TestFixtures.VersionedTomlConfig> storage =
            TestFixtures.storage(tempDir, TestFixtures.VersionedTomlConfig.class);

        storage.write(new TestFixtures.VersionedTomlConfig());

        assertTrue(Files.readString(tempDir.resolve("versioned-toml.toml")).contains("configVersion = 2"));
    }

    @Test
    void testLeavesUnversionedConfigsUntouched(@TempDir Path tempDir) throws IOException {
        ConfigStorage<TestFixtures.SimpleConfig> storage =
            TestFixtures.storage(tempDir, TestFixtures.SimpleConfig.class);

        storage.write(new TestFixtures.SimpleConfig());

        assertFalse(Files.readString(tempDir.resolve("simple.json5")).contains("configVersion"));
    }

    @Test
    void testRejectsAFileWrittenByANewerVersionOfTheMod(@TempDir Path tempDir) throws IOException {
        write(tempDir, "versioned.json5", "{\"configVersion\":9,\"hud\":{\"scale\":1}}");
        ConfigStorage<TestFixtures.VersionedConfig> storage =
            TestFixtures.storage(tempDir, TestFixtures.VersionedConfig.class);

        LiteConfigException failure = assertThrows(
            LiteConfigException.class, storage::read);

        assertEquals(ConfigError.CONFIG_VERSION_TOO_NEW, failure.error());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "null", "\"2\"", "true", "{}", "[]", "0", "-1", "1.5", "2147483648", "Infinity", "NaN"
    })
    void testRejectsMalformedStoredVersions(String marker, @TempDir Path tempDir) throws IOException {
        write(tempDir, "versioned.json5", "{\"configVersion\":" + marker + "}");

        LiteConfigException failure = assertThrows(
            LiteConfigException.class,
            () -> TestFixtures.storage(tempDir, TestFixtures.VersionedConfig.class).read());

        assertEquals(ConfigError.MALFORMED_CONFIG_DATA, failure.error());
    }

    @Test
    void testReportsAGapInTheDeclaredSteps(@TempDir Path tempDir) throws IOException {
        write(tempDir, "gapped.json5", "{\"scale\":7}");
        ConfigStorage<TestFixtures.GappedMigrationConfig> storage =
            TestFixtures.storage(tempDir, TestFixtures.GappedMigrationConfig.class);

        LiteConfigException failure = assertThrows(
            LiteConfigException.class, storage::read);

        assertEquals(ConfigError.MISSING_CONFIG_MIGRATION, failure.error());
    }

    @Test
    void testReportsAGapDuringDeclarationValidation() {
        assertTrue(ConfigMigrations.of(TestFixtures.GappedMigrationConfig.class)
            .problems()
            .stream()
            .anyMatch(problem -> problem.contains("missing migration from version 2 to 3")));
    }

    @Test
    void testReportsStepsBeyondTheDeclaredVersion() {
        assertTrue(ConfigMigrations.of(TestFixtures.UnreachableMigrationConfig.class)
            .problems()
            .stream()
            .anyMatch(problem -> problem.contains("but the config version is 2")));
    }

    @Test
    void testReportsAStepThatThrows(@TempDir Path tempDir) throws IOException {
        write(tempDir, "throwing-migration.json5", "{\"scale\":7}");
        ConfigStorage<TestFixtures.ThrowingMigrationConfig> storage =
            TestFixtures.storage(tempDir, TestFixtures.ThrowingMigrationConfig.class);

        LiteConfigException failure = assertThrows(
            LiteConfigException.class, storage::read);

        assertEquals(ConfigError.MIGRATION_FAILED, failure.error());
    }

    @Test
    void testClassifiesLibraryFailuresFromStepsAndPreservesTheirRootCause(@TempDir Path tempDir)
        throws IOException {
        write(tempDir, "library-failure-migration.json5", "{\"value\":1}");

        LiteConfigException failure = assertThrows(
            LiteConfigException.class,
            () -> TestFixtures.storage(tempDir, LibraryFailureMigrationConfig.class).read());

        assertEquals(ConfigError.MIGRATION_FAILED, failure.error());
        assertEquals(IllegalStateException.class, failure.getCause().getClass());
        assertEquals("root", failure.getCause().getMessage());
    }

    @Test
    void testReportsTwoStepsDeclaredForTheSameVersion() {
        assertEquals(
            1,
            ConfigMigrations.of(TestFixtures.DuplicateMigrationConfig.class).problems().size());
    }

    @Test
    void testReportsAStepThatIsNotStatic() {
        assertTrue(ConfigMigrations.of(TestFixtures.InstanceMigrationConfig.class)
            .problems()
            .getFirst()
            .contains("must be static"));
    }

    @Test
    void testFindsNoProblemsInASoundConfig() {
        assertTrue(ConfigMigrations.of(TestFixtures.VersionedConfig.class).problems().isEmpty());
    }

    private static void write(Path tempDir, String fileName, String text) throws IOException {
        Files.writeString(tempDir.resolve(fileName), text);
    }
}
