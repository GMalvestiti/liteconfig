package com.gmalvestiti.minecraft.liteconfig.storage;

import com.gmalvestiti.minecraft.liteconfig.api.annotations.Config;
import com.gmalvestiti.minecraft.liteconfig.api.ConfigType;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigError;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigScope;
import com.gmalvestiti.minecraft.liteconfig.exception.LiteConfigException;
import com.gmalvestiti.minecraft.liteconfig.support.TestFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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

    @Config(name = "name", path = "../othermod")
    static class OtherNamespaceConfig {}

    @Config(name = "name", path = "../othermod", type = ConfigType.WORLD)
    static class OtherWorldNamespaceConfig {}

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

    @Config(name = "named", path = "nested", modIdDirectory = false)
    static class UnscopedConfig {}

    @Config(name = "named", path = "nested", modIdDirectory = false)
    static class ClashingUnscopedConfig {}

    @Config(name = "world", path = "rules", type = ConfigType.WORLD)
    static class WorldConfig {}

    @Config(name = "world", path = "rules", type = ConfigType.WORLD, modIdDirectory = false)
    static class UnscopedWorldConfig {}

    @Config(name = "world", path = "rules")
    static class InstallationWorldNameConfig {}

    @Test
    void testOwnershipAndNamespaceDefaults() {
        Config declaration = PathConfig.class.getAnnotation(Config.class);
        assertEquals(ConfigType.SERVER, declaration.type());
        assertTrue(declaration.modIdDirectory());
    }

    @Test
    void testScopesPathsByModIdUnlessDisabled(@TempDir Path tempDir) {
        ConfigFileOwnership ownership = new ConfigFileOwnership();
        ConfigPathResolver first = new ConfigPathResolver(tempDir, new ConfigScope("first"), ownership);
        ConfigPathResolver second = new ConfigPathResolver(tempDir, new ConfigScope("second"), ownership);

        assertEquals(tempDir.resolve("first").resolve("nested").resolve("named.json5"),
            first.resolveForConfig(PathConfig.class));
        assertEquals(tempDir.resolve("second").resolve("nested").resolve("named.json5"),
            second.resolveForConfig(PathConfig.class));
        assertEquals(tempDir.resolve("nested").resolve("named.json5"),
            first.resolveForConfig(UnscopedConfig.class));
        assertEquals(ConfigError.CONFLICTING_CONFIG_PATH,
            assertThrows(LiteConfigException.class,
                () -> second.resolveForConfig(ClashingUnscopedConfig.class)).error());
    }

    @Test
    void testRejectsUnsafeModIdDirectoryComponentsEvenWithoutAnActiveWorld(@TempDir Path tempDir) {
        String[] unsafe = {
            "", " ", ".", "..", "../other", "..\\other", "first/second", "first\\second",
            "/absolute", "\\absolute", "C:\\absolute", "C:relative", "\\\\server\\share",
            "bad\u0000id", "bad\nid", "mod:stream", "mod.", "mod ", "CON", "nul.txt", "COM1", "LPT9"
        };
        for (String modId : unsafe) {
            for (Class<?> configType : new Class<?>[] {PathConfig.class, WorldConfig.class}) {
                ConfigPathResolver resolver =
                    new ConfigPathResolver(tempDir, new ConfigScope(modId), new ConfigFileOwnership());
                LiteConfigException failure = assertThrows(LiteConfigException.class,
                    () -> resolver.resolveForConfig(configType), "unsafe mod id: " + modId);
                assertEquals(ConfigError.INVALID_CONFIG_PATH, failure.error());
            }
        }
    }

    @Test
    void testRejectsPathsThatLeaveTheModNamespaceButStayInsideTheBaseDirectory(@TempDir Path tempDir) {
        for (Class<?> configType : new Class<?>[] {OtherNamespaceConfig.class, OtherWorldNamespaceConfig.class}) {
            ConfigPathResolver resolver =
                new ConfigPathResolver(tempDir, new ConfigScope("mymod"), new ConfigFileOwnership());
            assertEquals(ConfigError.INVALID_CONFIG_PATH,
                assertThrows(LiteConfigException.class, () -> resolver.resolveForConfig(configType)).error());
        }
    }

    @Test
    void testAcceptsSafeSingleComponentNamespacesAndSkipsNamespaceValidationWhenDisabled(@TempDir Path tempDir) {
        for (String modId : new String[] {"mymod", "my-mod", "my_mod", "my.mod"}) {
            ConfigPathResolver resolver =
                new ConfigPathResolver(tempDir, new ConfigScope(modId), new ConfigFileOwnership());
            assertEquals(tempDir.resolve(modId).resolve("nested").resolve("named.json5"),
                resolver.resolveForConfig(PathConfig.class));
        }
        ConfigPathResolver unscoped =
            new ConfigPathResolver(tempDir, new ConfigScope("not/a/directory"), new ConfigFileOwnership());
        assertEquals(tempDir.resolve("nested").resolve("named.json5"),
            unscoped.resolveForConfig(UnscopedConfig.class));
    }

    @Test
    void testWorldPathsAreUnclaimedUntilBoundAndOldClaimsAreReleased(@TempDir Path tempDir) {
        ConfigFileOwnership ownership = new ConfigFileOwnership();
        ConfigPathResolver resolver = new ConfigPathResolver(tempDir, TestFixtures.SCOPE, ownership);
        assertEquals(null, resolver.resolveForConfig(WorldConfig.class));
        assertEquals(ConfigType.WORLD, resolver.type());
        assertTrue(resolver.activePath().isEmpty());
        assertEquals(tempDir.toAbsolutePath().normalize(), resolver.baseDirectory());
        resolver.resolveForConfig(InstallationWorldNameConfig.class);

        Path firstWorld = tempDir.resolve("first-world");
        Path secondWorld = tempDir.resolve("second-world");
        resolver.bindWorld(WorldConfig.class, firstWorld);
        Path firstFile = firstWorld.resolve("liteconfig").resolve(TestFixtures.SCOPE.modId())
            .resolve("rules").resolve("world.json5");
        assertEquals(firstFile, resolver.currentPathForConfig(WorldConfig.class));
        assertEquals(firstFile, resolver.activePath().orElseThrow());
        resolver.bindWorld(WorldConfig.class, secondWorld);

        ownership.claim(InstallationWorldNameConfig.class, firstFile, TestFixtures.SCOPE);
        ownership.release(InstallationWorldNameConfig.class, firstFile);
        Path secondFile = resolver.currentPathForConfig(WorldConfig.class);
        resolver.bindWorld(WorldConfig.class, null);
        assertEquals(null, resolver.currentPathForConfig(WorldConfig.class));
        assertTrue(resolver.activePath().isEmpty());
        assertEquals(ConfigType.WORLD, resolver.type());
        assertEquals(tempDir.toAbsolutePath().normalize(), resolver.baseDirectory());
        ownership.claim(InstallationWorldNameConfig.class, secondFile, TestFixtures.SCOPE);
        ownership.release(InstallationWorldNameConfig.class, secondFile);
    }

    @Test
    void testWorldNamespaceCanBeDisabled(@TempDir Path tempDir) {
        ConfigPathResolver resolver = new ConfigPathResolver(tempDir, TestFixtures.SCOPE, new ConfigFileOwnership());
        Path world = tempDir.resolve("world");
        resolver.bindWorld(UnscopedWorldConfig.class, world);
        assertEquals(world.resolve("liteconfig").resolve("rules").resolve("world.json5"),
            resolver.currentPathForConfig(UnscopedWorldConfig.class));
    }

    @Test
    void testResolvesPathsConsistently(@TempDir Path tempDir) {
        ConfigPathResolver resolver = new ConfigPathResolver(tempDir, TestFixtures.SCOPE, new ConfigFileOwnership());
        Path first = resolver.resolveForConfig(PathConfig.class);
        Path second = resolver.resolveForConfig(PathConfig.class);
        Path jsonSuffix = resolver.resolveForConfig(JsonSuffixConfig.class);

        assertSame(first, second, "installation lookups reuse the validated immutable path");
        assertSame(first, resolver.currentPathForConfig(PathConfig.class));
        assertEquals(ConfigType.SERVER, resolver.type());
        assertEquals(first, resolver.activePath().orElseThrow());
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
    void testCloseReleasesEveryReferenceWithoutReleasingOtherResolvers(@TempDir Path tempDir) {
        ConfigFileOwnership ownership = new ConfigFileOwnership();
        ConfigPathResolver resolver = new ConfigPathResolver(tempDir, TestFixtures.SCOPE, ownership);
        ConfigPathResolver other = new ConfigPathResolver(tempDir, TestFixtures.SCOPE, ownership);
        resolver.resolveForConfig(TestFixtures.SimpleConfig.class);
        resolver.resolveForConfig(TestFixtures.SimpleConfig.class);
        other.resolveForConfig(TestFixtures.SimpleConfig.class);

        resolver.closeForConfig(TestFixtures.SimpleConfig.class);
        assertThrows(
            LiteConfigException.class,
            () -> ownership.claim(ClashingSimpleConfig.class,
                tempDir.resolve("mod").resolve("simple.json5"), TestFixtures.SCOPE));

        resolver.closeForConfig(TestFixtures.SimpleConfig.class);
        other.closeForConfig(TestFixtures.SimpleConfig.class);
        ConfigPathResolver replacement = new ConfigPathResolver(tempDir, TestFixtures.SCOPE, ownership);
        assertEquals(tempDir.resolve(TestFixtures.SCOPE.modId()).resolve("simple.json5"),
            replacement.resolveForConfig(ClashingSimpleConfig.class));
    }

    @Test
    void testIndependentOwnershipsDoNotSeeEachOthersClaims(@TempDir Path tempDir) {
        new ConfigPathResolver(tempDir, TestFixtures.SCOPE, new ConfigFileOwnership())
            .resolveForConfig(TestFixtures.SimpleConfig.class);

        Path clashing = new ConfigPathResolver(tempDir, TestFixtures.SCOPE, new ConfigFileOwnership())
            .resolveForConfig(ClashingSimpleConfig.class);

        assertTrue(clashing.toString().endsWith("simple.json5"));
    }

    @Test
    void testCachedWorldPathRebasesOnTheSaveRootAndCloseKeepsItInactive(@TempDir Path tempDir) {
        ConfigPathResolver resolver =
            new ConfigPathResolver(tempDir.resolve("installation"), TestFixtures.SCOPE, new ConfigFileOwnership());
        assertNull(resolver.currentPathForConfig(WorldConfig.class));
        Path worldRoot = tempDir.resolve("saves").resolve("world");

        resolver.bindWorld(WorldConfig.class, worldRoot.resolve("folder").resolve(".."));

        Path expected = worldRoot.resolve("liteconfig").resolve(TestFixtures.SCOPE.modId())
            .resolve("rules").resolve("world.json5");
        assertEquals(expected, resolver.currentPathForConfig(WorldConfig.class));
        assertFalse(resolver.bindWorld(WorldConfig.class, worldRoot), "equivalent save roots do not rebind");
        resolver.closeForConfig(WorldConfig.class);
        assertEquals(ConfigType.WORLD, resolver.type());
        assertTrue(resolver.activePath().isEmpty());
        assertFalse(resolver.bindWorld(WorldConfig.class, worldRoot));
    }
}
