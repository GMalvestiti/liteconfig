package com.gmalvestiti.minecraft.liteconfig.storage;

import com.gmalvestiti.minecraft.liteconfig.api.annotations.Config;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigError;
import com.gmalvestiti.minecraft.liteconfig.exception.LiteConfigException;
import com.gmalvestiti.minecraft.liteconfig.support.TestFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigPathResolverTest {

    @Config(name = "named", path = "nested")
    static class PathConfig {
    }

    @Config(name = "bad/name")
    static class BadNameConfig {
    }

    @Config(name = "name", path = "../escape")
    static class EscapePathConfig {
    }

    @Config(name = "already.json5")
    static class JsonSuffixConfig {
    }

    @Config(name = ".json5")
    static class DotJsonNameConfig {
    }

    @Config(name = "bad\u0000name")
    static class InvalidPathCharsNameConfig {
    }

    @Config(name = "name", path = "bad\u0000path")
    static class InvalidPathCharsPathConfig {
    }

    @Config(name = "simple")
    static class ClashingSimpleConfig {
    }

    @Test
    void testResolvesPathsConsistently(@TempDir Path tempDir) {
        ConfigPathResolver resolver = new ConfigPathResolver(tempDir, TestFixtures.SCOPE, new ConfigFileOwnership());
        Path first = resolver.resolveForConfig(PathConfig.class);
        Path second = resolver.resolveForConfig(PathConfig.class);
        Path jsonSuffix = resolver.resolveForConfig(JsonSuffixConfig.class);

        assertEquals(first, second);
        assertTrue(first.toString().endsWith("nested" + java.io.File.separator + "named.json5"));
        assertTrue(jsonSuffix.toString().endsWith("already.json5"));
    }

    @Test
    void testRejectsInvalidDefinitions(@TempDir Path tempDir) {
        ConfigPathResolver resolver = new ConfigPathResolver(tempDir, TestFixtures.SCOPE, new ConfigFileOwnership());
        assertThrows(LiteConfigException.class, () -> resolver.resolveForConfig(String.class));
        assertThrows(LiteConfigException.class, () -> resolver.resolveForConfig(BadNameConfig.class));
        assertThrows(LiteConfigException.class, () -> resolver.resolveForConfig(EscapePathConfig.class));
        assertThrows(LiteConfigException.class, () -> resolver.resolveForConfig(DotJsonNameConfig.class));
        assertThrows(LiteConfigException.class, () -> resolver.resolveForConfig(InvalidPathCharsNameConfig.class));
        assertThrows(LiteConfigException.class, () -> resolver.resolveForConfig(InvalidPathCharsPathConfig.class));
    }

    @Test
    void testRejectsTwoTypesThatClaimTheSameFile(@TempDir Path tempDir) {
        ConfigPathResolver resolver = new ConfigPathResolver(tempDir, TestFixtures.SCOPE, new ConfigFileOwnership());
        resolver.resolveForConfig(TestFixtures.SimpleConfig.class);

        LiteConfigException failure = assertThrows(
            LiteConfigException.class,
            () -> resolver.resolveForConfig(ClashingSimpleConfig.class)
        );
        assertEquals(ConfigError.CONFLICTING_CONFIG_PATH, failure.error());
    }

    @Test
    void testRejectsTwoTypesThatClaimTheSameFileAcrossResolversSharingOwnership(@TempDir Path tempDir) {
        ConfigFileOwnership ownership = new ConfigFileOwnership();
        new ConfigPathResolver(tempDir, TestFixtures.SCOPE, ownership)
            .resolveForConfig(TestFixtures.SimpleConfig.class);

        LiteConfigException failure = assertThrows(
            LiteConfigException.class,
            () -> new ConfigPathResolver(tempDir, TestFixtures.SCOPE, ownership)
                .resolveForConfig(ClashingSimpleConfig.class)
        );

        assertEquals(ConfigError.CONFLICTING_CONFIG_PATH, failure.error());
    }

    @Test
    void testAllowsSameTypeToClaimTheSameFileAcrossResolvers(@TempDir Path tempDir) {
        ConfigFileOwnership ownership = new ConfigFileOwnership();
        Path first = new ConfigPathResolver(tempDir, TestFixtures.SCOPE, ownership)
            .resolveForConfig(TestFixtures.SimpleConfig.class);
        Path second = new ConfigPathResolver(tempDir, TestFixtures.SCOPE, ownership)
            .resolveForConfig(TestFixtures.SimpleConfig.class);

        assertEquals(first, second);
    }

    @Test
    void testReleaseRemovesOnlyTheMatchingClaim(@TempDir Path tempDir) {
        ConfigFileOwnership ownership = new ConfigFileOwnership();
        ConfigPathResolver resolver = new ConfigPathResolver(tempDir, TestFixtures.SCOPE, ownership);
        resolver.resolveForConfig(TestFixtures.SimpleConfig.class);
        resolver.resolveForConfig(TestFixtures.SimpleConfig.class);

        resolver.releaseForConfig(TestFixtures.SimpleConfig.class);
        assertThrows(
            LiteConfigException.class,
            () -> resolver.resolveForConfig(ClashingSimpleConfig.class));

        resolver.releaseForConfig(TestFixtures.SimpleConfig.class);
        assertTrue(resolver.resolveForConfig(ClashingSimpleConfig.class).endsWith("simple.json5"));
    }

    @Test
    void testIndependentOwnershipsDoNotSeeEachOthersClaims(@TempDir Path tempDir) {
        new ConfigPathResolver(tempDir, TestFixtures.SCOPE, new ConfigFileOwnership())
            .resolveForConfig(TestFixtures.SimpleConfig.class);

        Path clashing = new ConfigPathResolver(tempDir, TestFixtures.SCOPE, new ConfigFileOwnership())
            .resolveForConfig(ClashingSimpleConfig.class);

        assertTrue(clashing.toString().endsWith("simple.json5"));
    }
}
