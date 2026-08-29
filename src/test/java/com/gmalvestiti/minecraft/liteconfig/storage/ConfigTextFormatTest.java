package com.gmalvestiti.minecraft.liteconfig.storage;

import com.gmalvestiti.minecraft.liteconfig.exception.ConfigError;
import com.gmalvestiti.minecraft.liteconfig.exception.LiteConfigException;
import com.gmalvestiti.minecraft.liteconfig.support.TestFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigTextFormatTest {

    @Test
    void testWritesJsonUnderTheNamesAndCommentsTheEntryAnnotationDeclares(@TempDir Path tempDir) throws IOException {
        ConfigStorage<TestFixtures.EntryConfig> storage =
            TestFixtures.storage(tempDir, TestFixtures.EntryConfig.class);

        storage.write(new TestFixtures.EntryConfig());
        String written = Files.readString(tempDir.resolve("entries.json5"));
        assertTrue(written.contains("hud_scale"), "@Entry(name) renames the property on disk");
        assertTrue(written.contains("Scale of the HUD overlay."));
        assertTrue(written.contains("Settings for the entry fixture."));
        assertTrue(written.contains("max_depth"), "nested objects are renamed too");
    }

    @Test
    void testRoundTripsJsonThroughTheRenamedProperties(@TempDir Path tempDir) {
        ConfigStorage<TestFixtures.EntryConfig> storage =
            TestFixtures.storage(tempDir, TestFixtures.EntryConfig.class);
        TestFixtures.EntryConfig written = new TestFixtures.EntryConfig();
        written.hudScale = 9;
        written.section.maxDepth = 11;

        storage.write(written);
        TestFixtures.EntryConfig read = storage.read();

        assertEquals(9, read.hudScale);
        assertEquals(11, read.section.maxDepth);
    }

    @Test
    void testWritesTomlForTypesThatDeclareIt(@TempDir Path tempDir) throws IOException {
        ConfigStorage<TestFixtures.TomlConfig> storage =
            TestFixtures.storage(tempDir, TestFixtures.TomlConfig.class);

        storage.write(new TestFixtures.TomlConfig());
        String written = Files.readString(tempDir.resolve("toml-fixture.toml"));
        assertTrue(written.contains("#A TOML-backed fixture."), "the class comment heads the file");
        assertTrue(written.contains("How loud, from 0 to 10."));
        assertTrue(written.contains("volume = 5"), "an integer field must not be written as a float");
        assertFalse(written.contains("volume = 5.0"));
        assertTrue(written.contains("display_name = \"player\""));
        assertTrue(written.contains("[section]"), "nested objects become tables");
    }

    @Test
    void testRoundTripsToml(@TempDir Path tempDir) {
        ConfigStorage<TestFixtures.TomlConfig> storage =
            TestFixtures.storage(tempDir, TestFixtures.TomlConfig.class);
        TestFixtures.TomlConfig written = new TestFixtures.TomlConfig();
        written.volume = 8;
        written.displayName = "steve";
        written.tags.add("c");
        written.section.ratio = 0.25;
        written.section.enabled = false;

        storage.write(written);
        TestFixtures.TomlConfig read = storage.read();

        assertEquals(8, read.volume);
        assertEquals("steve", read.displayName);
        assertEquals(java.util.List.of("a", "b", "c"), read.tags);
        assertEquals(0.25, read.section.ratio);
        assertFalse(read.section.enabled);
    }

    @Test
    void testReportsMalformedTomlLikeAnyOtherUnreadableFile(@TempDir Path tempDir) throws IOException {
        ConfigStorage<TestFixtures.TomlConfig> storage =
            TestFixtures.storage(tempDir, TestFixtures.TomlConfig.class);
        Files.writeString(tempDir.resolve("toml-fixture.toml"), "volume = = 3");

        LiteConfigException failure = assertThrows(
            LiteConfigException.class,
            storage::read
        );
        assertEquals(ConfigError.MALFORMED_CONFIG_DATA, failure.error());
    }

    @Test
    void testRendersMultiLineCommentsAsOneJsonBlock(@TempDir Path tempDir) throws IOException {
        ConfigStorage<TestFixtures.AwkwardCommentConfig> storage =
            TestFixtures.storage(tempDir, TestFixtures.AwkwardCommentConfig.class);

        storage.write(new TestFixtures.AwkwardCommentConfig());
        String written = Files.readString(tempDir.resolve("awkward-comments.json5"));

        assertTrue(written.contains("/*"), "several lines share one block comment");
        assertTrue(written.contains(" * Two lines,"));
        assertTrue(written.contains(" * so this renders as a block."));
    }

    @Test
    void testKeepsFilesReadableWhenACommentContainsABlockTerminator(@TempDir Path tempDir) {
        ConfigStorage<TestFixtures.AwkwardCommentConfig> storage =
            TestFixtures.storage(tempDir, TestFixtures.AwkwardCommentConfig.class);
        TestFixtures.AwkwardCommentConfig written = new TestFixtures.AwkwardCommentConfig();
        written.value = 42;

        storage.write(written);

        assertEquals(
            42,
            Objects.requireNonNull(storage.read()).value,
            "a comment must never be able to truncate the file it documents");
    }

    @Test
    void testCommentsOneTomlLinePerEntry(@TempDir Path tempDir) throws IOException {
        ConfigStorage<TestFixtures.AwkwardCommentTomlConfig> storage =
            TestFixtures.storage(tempDir, TestFixtures.AwkwardCommentTomlConfig.class);
        TestFixtures.AwkwardCommentTomlConfig written = new TestFixtures.AwkwardCommentTomlConfig();
        written.value = 42;

        storage.write(written);
        String text = Files.readString(tempDir.resolve("awkward-comments-toml.toml"));

        assertTrue(text.contains("#Two lines,"));
        assertTrue(text.contains("#so this renders as a block."));
        assertFalse(text.contains("/*"), "TOML has no block comment to escape into");
        assertEquals(42, Objects.requireNonNull(storage.read()).value);
    }

    @Test
    void testExcludesFieldsAnnotatedWithConfigIgnore(@TempDir Path tempDir) throws IOException {
        ConfigStorage<TestFixtures.IgnoredFieldConfig> storage =
            TestFixtures.storage(tempDir, TestFixtures.IgnoredFieldConfig.class);

        storage.write(new TestFixtures.IgnoredFieldConfig());
        String written = Files.readString(tempDir.resolve("ignored-field.json5"));

        assertTrue(written.contains("persisted"), "non-ignored fields must be written");
        assertFalse(written.contains("ignored"), "@Ignore fields must not appear in the file");
    }

    @Test
    void testDoesNotRestoreIgnoredFieldValueFromFile(@TempDir Path tempDir) throws IOException {
        ConfigStorage<TestFixtures.IgnoredFieldConfig> storage =
            TestFixtures.storage(tempDir, TestFixtures.IgnoredFieldConfig.class);

        Files.writeString(tempDir.resolve("ignored-field.json5"), "{\"persisted\":7,\"ignored\":42}");

        TestFixtures.IgnoredFieldConfig loaded = storage.read();

        assertEquals(7, loaded.persisted);
        assertEquals(99, loaded.ignored, "@Ignore fields keep their constructor default after load");
    }

    @Test
    void testIgnoresCommentWhenConfigIgnoreAndConfigEntryAreCombined(@TempDir Path tempDir) throws IOException {
        ConfigStorage<TestFixtures.IgnoredWithEntryConfig> storage =
            TestFixtures.storage(tempDir, TestFixtures.IgnoredWithEntryConfig.class);

        storage.write(new TestFixtures.IgnoredWithEntryConfig());
        String written = Files.readString(tempDir.resolve("ignored-with-entry.json5"));

        assertFalse(written.contains("ignored"), "@Ignore must exclude the field even when @Entry is also present");
        assertFalse(written.contains("This comment must never reach the file."), "@Entry comment must not be written for an ignored field");
    }

    @Test
    void testWritesOnlyDeclaredEntryComments(@TempDir Path tempDir) throws IOException {
        ConfigStorage<TestFixtures.ConstrainedConfig> storage =
            TestFixtures.storage(tempDir, TestFixtures.ConstrainedConfig.class);

        storage.write(new TestFixtures.ConstrainedConfig());
        String written = Files.readString(tempDir.resolve("constrained.json5"));

        assertTrue(written.contains("Scale of the HUD."));
        assertFalse(written.contains("Default:"));
        assertFalse(written.contains("Range:"));
        assertFalse(written.contains("Minimum:"));
        assertFalse(written.contains("Maximum length:"));
        assertFalse(written.contains("Must match:"));
        assertFalse(written.contains("Length:"));
        assertFalse(written.contains("Allowed values:"));
    }

    @Test
    void testDoesNotGenerateRestartComments(@TempDir Path tempDir) throws IOException {
        ConfigStorage<TestFixtures.EntryConfig> storage =
            TestFixtures.storage(tempDir, TestFixtures.EntryConfig.class);

        storage.write(new TestFixtures.EntryConfig());
        String written = Files.readString(tempDir.resolve("entries.json5"));

        assertFalse(written.contains("Takes effect after a restart."));
    }

    @Test
    void testDoesNotGenerateVersionComments(@TempDir Path tempDir) throws IOException {
        ConfigStorage<TestFixtures.VersionedTomlConfig> storage =
            TestFixtures.storage(tempDir, TestFixtures.VersionedTomlConfig.class);

        storage.write(new TestFixtures.VersionedTomlConfig());
        String written = Files.readString(tempDir.resolve("versioned-toml.toml"));

        assertFalse(written.contains("#Revision of this file."));
    }

    @Test
    void testLeavesFilesUnchangedForConfigsThatDeclareNothingExtra(@TempDir Path tempDir) throws IOException {
        ConfigStorage<TestFixtures.SimpleConfig> storage =
            TestFixtures.storage(tempDir, TestFixtures.SimpleConfig.class);

        storage.write(new TestFixtures.SimpleConfig());
        String written = Files.readString(tempDir.resolve("simple.json5"));

        assertFalse(written.contains("Default:"));
        assertFalse(written.contains("configVersion"));
    }
}
