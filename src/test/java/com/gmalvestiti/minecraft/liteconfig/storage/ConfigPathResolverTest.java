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

    private static ConfigPathResolver resolver(Class<?> type, Path base, ConfigScope scope, ConfigFileOwnership ownership) {
        return new ConfigPathResolver(type, base, scope, ownership);
    }

    private static ConfigPathResolver resolver(Class<?> type, Path base) {
        return resolver(type, base, TestFixtures.SCOPE, new ConfigFileOwnership());
    }

    @Test
    void testOwnershipAndNamespaceDefaults() {
        Config declaration = PathConfig.class.getAnnotation(Config.class);
        assertEquals(ConfigType.SERVER, declaration.type());
        assertTrue(declaration.modIdDirectory());
    }

    @Test
    void testScopesPathsByModIdUnlessDisabled(@TempDir Path tempDir) {
        ConfigFileOwnership ownership = new ConfigFileOwnership();
        ConfigPathResolver first = resolver(PathConfig.class, tempDir, new ConfigScope("first"), ownership);
        ConfigPathResolver second = resolver(PathConfig.class, tempDir, new ConfigScope("second"), ownership);

        assertEquals(tempDir.resolve("first").resolve("nested").resolve("named.json5"), first.resolve());
        assertEquals(tempDir.resolve("second").resolve("nested").resolve("named.json5"), second.resolve());
        assertEquals(tempDir.resolve("nested").resolve("named.json5"),
            resolver(UnscopedConfig.class, tempDir, new ConfigScope("first"), ownership).resolve());
        assertEquals(ConfigError.CONFLICTING_CONFIG_PATH,
            assertThrows(LiteConfigException.class,
                () -> resolver(ClashingUnscopedConfig.class, tempDir, new ConfigScope("second"), ownership)
                    .resolve()).error());
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
                LiteConfigException failure = assertThrows(LiteConfigException.class,
                    () -> resolver(configType, tempDir, new ConfigScope(modId), new ConfigFileOwnership()),
                    "unsafe mod id: " + modId);
                assertEquals(ConfigError.INVALID_CONFIG_PATH, failure.error());
            }
        }
    }

    @Test
    void testRejectsPathsThatLeaveTheModNamespaceButStayInsideTheBaseDirectory(@TempDir Path tempDir) {
        for (Class<?> configType : new Class<?>[] {OtherNamespaceConfig.class, OtherWorldNamespaceConfig.class}) {
            assertEquals(ConfigError.INVALID_CONFIG_PATH,
                assertThrows(LiteConfigException.class,
                    () -> resolver(configType, tempDir, new ConfigScope("mymod"), new ConfigFileOwnership()))
                    .error());
        }
    }

    @Test
    void testAcceptsSafeSingleComponentNamespacesAndSkipsNamespaceValidationWhenDisabled(@TempDir Path tempDir) {
        for (String modId : new String[] {"mymod", "my-mod", "my_mod", "my.mod"}) {
            ConfigPathResolver resolver =
                resolver(PathConfig.class, tempDir, new ConfigScope(modId), new ConfigFileOwnership());
            assertEquals(tempDir.resolve(modId).resolve("nested").resolve("named.json5"), resolver.resolve());
        }
        ConfigPathResolver unscoped =
            resolver(UnscopedConfig.class, tempDir, new ConfigScope("not/a/directory"), new ConfigFileOwnership());
        assertEquals(tempDir.resolve("nested").resolve("named.json5"), unscoped.resolve());
    }

    @Test
    void testWorldPathsAreUnclaimedUntilBoundAndOldClaimsAreReleased(@TempDir Path tempDir) {
        ConfigFileOwnership ownership = new ConfigFileOwnership();
        ConfigPathResolver resolver = resolver(WorldConfig.class, tempDir, TestFixtures.SCOPE, ownership);
        assertNull(resolver.resolve());
        assertEquals(ConfigType.WORLD, resolver.type());
        assertTrue(resolver.activePath().isEmpty());
        assertEquals(tempDir.toAbsolutePath().normalize(), resolver.baseDirectory());

        Path firstWorld = tempDir.resolve("first-world");
        Path secondWorld = tempDir.resolve("second-world");
        resolver.bindWorld(firstWorld);
        Path firstFile = firstWorld.resolve("liteconfig").resolve(TestFixtures.SCOPE.modId())
            .resolve("rules").resolve("world.json5");
        assertEquals(firstFile, resolver.currentPath());
        assertEquals(firstFile, resolver.activePath().orElseThrow());
        assertThrows(LiteConfigException.class,
            () -> ownership.claim(InstallationWorldNameConfig.class, firstFile, TestFixtures.SCOPE));

        resolver.bindWorld(secondWorld);
        ownership.claim(InstallationWorldNameConfig.class, firstFile, TestFixtures.SCOPE);
        ownership.release(InstallationWorldNameConfig.class, firstFile);

        Path secondFile = resolver.currentPath();
        resolver.bindWorld(null);
        assertNull(resolver.currentPath());
        assertTrue(resolver.activePath().isEmpty());
        ownership.claim(InstallationWorldNameConfig.class, secondFile, TestFixtures.SCOPE);
        ownership.release(InstallationWorldNameConfig.class, secondFile);
    }

    @Test
    void testWorldNamespaceCanBeDisabled(@TempDir Path tempDir) {
        ConfigPathResolver resolver = resolver(UnscopedWorldConfig.class, tempDir);
        Path world = tempDir.resolve("world");
        resolver.bindWorld(world);
        assertEquals(world.resolve("liteconfig").resolve("rules").resolve("world.json5"), resolver.currentPath());
    }

    @Test
    void testResolvesPathsConsistently(@TempDir Path tempDir) {
        ConfigPathResolver resolver = resolver(PathConfig.class, tempDir);
        Path first = resolver.resolve();
        Path second = resolver.resolve();

        assertSame(first, second, "installation lookups reuse the validated immutable path");
        assertSame(first, resolver.currentPath());
        assertEquals(ConfigType.SERVER, resolver.type());
        assertEquals(first, resolver.activePath().orElseThrow());
        assertTrue(first.toString().endsWith("nested" + java.io.File.separator + "named.json5"));
        assertTrue(resolver(JsonSuffixConfig.class, tempDir).resolve().toString().endsWith("already.json5"));
    }

    @Test
    void testRejectsInvalidDefinitions(@TempDir Path tempDir) {
        for (Class<?> invalid : new Class<?>[] {
            String.class, BadNameConfig.class, EscapePathConfig.class, DotJsonNameConfig.class,
            InvalidPathCharsNameConfig.class, InvalidPathCharsPathConfig.class
        }) {
            assertThrows(LiteConfigException.class, () -> resolver(invalid, tempDir), invalid.getName());
        }
    }

    @Test
    void testRejectsTwoTypesThatClaimTheSameFile(@TempDir Path tempDir) {
        ConfigFileOwnership ownership = new ConfigFileOwnership();
        resolver(TestFixtures.SimpleConfig.class, tempDir, TestFixtures.SCOPE, ownership).resolve();

        LiteConfigException failure = assertThrows(
            LiteConfigException.class,
            () -> resolver(ClashingSimpleConfig.class, tempDir, TestFixtures.SCOPE, ownership).resolve()
        );

        assertEquals(ConfigError.CONFLICTING_CONFIG_PATH, failure.error());
    }

    @Test
    void testAllowsSameTypeToClaimTheSameFileAcrossResolvers(@TempDir Path tempDir) {
        ConfigFileOwnership ownership = new ConfigFileOwnership();
        Path first = resolver(TestFixtures.SimpleConfig.class, tempDir, TestFixtures.SCOPE, ownership).resolve();
        Path second = resolver(TestFixtures.SimpleConfig.class, tempDir, TestFixtures.SCOPE, ownership).resolve();

        assertEquals(first, second);
    }

    @Test
    void testCloseReleasesOnlyItsOwnClaim(@TempDir Path tempDir) {
        ConfigFileOwnership ownership = new ConfigFileOwnership();
        ConfigPathResolver resolver =
            resolver(TestFixtures.SimpleConfig.class, tempDir, TestFixtures.SCOPE, ownership);
        ConfigPathResolver other =
            resolver(TestFixtures.SimpleConfig.class, tempDir, TestFixtures.SCOPE, ownership);
        resolver.resolve();
        resolver.resolve();
        other.resolve();

        resolver.close();
        resolver.close();
        assertThrows(
            LiteConfigException.class,
            () -> ownership.claim(ClashingSimpleConfig.class,
                tempDir.resolve("mod").resolve("simple.json5"), TestFixtures.SCOPE));

        other.close();
        assertEquals(tempDir.resolve(TestFixtures.SCOPE.modId()).resolve("simple.json5"),
            resolver(ClashingSimpleConfig.class, tempDir, TestFixtures.SCOPE, ownership).resolve());
    }

    @Test
    void testIndependentOwnershipsDoNotSeeEachOthersClaims(@TempDir Path tempDir) {
        resolver(TestFixtures.SimpleConfig.class, tempDir).resolve();

        Path clashing = resolver(ClashingSimpleConfig.class, tempDir).resolve();

        assertTrue(clashing.toString().endsWith("simple.json5"));
    }

    @Test
    void testCachedWorldPathRebasesOnTheSaveRootAndCloseKeepsItInactive(@TempDir Path tempDir) {
        ConfigPathResolver resolver = resolver(WorldConfig.class, tempDir.resolve("installation"));
        assertNull(resolver.currentPath());
        Path worldRoot = tempDir.resolve("saves").resolve("world");

        resolver.bindWorld(worldRoot.resolve("folder").resolve(".."));

        Path expected = worldRoot.resolve("liteconfig").resolve(TestFixtures.SCOPE.modId())
            .resolve("rules").resolve("world.json5");
        assertEquals(expected, resolver.currentPath());
        assertFalse(resolver.bindWorld(worldRoot), "equivalent save roots do not rebind");
        resolver.close();
        assertEquals(ConfigType.WORLD, resolver.type());
        assertTrue(resolver.activePath().isEmpty());
        assertFalse(resolver.bindWorld(worldRoot));
    }
}
