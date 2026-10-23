package com.gmalvestiti.minecraft.liteconfig.registry;

import com.gmalvestiti.minecraft.liteconfig.api.ConfigHolder;
import com.gmalvestiti.minecraft.liteconfig.api.FailurePolicy;
import com.gmalvestiti.minecraft.liteconfig.api.ConfigType;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Config;
import com.gmalvestiti.minecraft.liteconfig.context.ConfigSettings;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigScope;
import com.gmalvestiti.minecraft.liteconfig.storage.ConfigFileOwnership;
import com.gmalvestiti.minecraft.liteconfig.storage.ConfigPathResolver;
import com.gmalvestiti.minecraft.liteconfig.support.ConfigRegistryIsolation;
import com.gmalvestiti.minecraft.liteconfig.support.RegisteredConfigs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(ConfigRegistryIsolation.class)
class ReadFailurePersistenceTest {

    @Config(
        name = "startup-read-failure",
        readFailurePolicy = FailurePolicy.FALLBACK,
        writeFailurePolicy = FailurePolicy.STRICT
    )
    static class StartupConfig {
        public int value = 41;
    }

    @Config(
        name = "world-read-failure",
        type = ConfigType.WORLD,
        readFailurePolicy = FailurePolicy.FALLBACK,
        writeFailurePolicy = FailurePolicy.STRICT
    )
    static class WorldConfig {
        public int value = 73;
    }

    @Test
    void startupIoFailureDoesNotTryToPersistDefaults(@TempDir Path directory) throws IOException {
        Path configFile = configPath(StartupConfig.class, directory);
        Files.createDirectories(configFile);

        try (ConfigHolder<StartupConfig> holder = RegisteredConfigs.create(
            settings(StartupConfig.class, directory)).access()) {
            assertEquals(41, holder.data().value);
            assertTrue(Files.isDirectory(configFile));
        }
    }

    @Test
    void worldIoFailureDoesNotTryToPersistDefaults(@TempDir Path directory) throws IOException {
        Path installation = directory.resolve("installation");
        Path world = directory.resolve("world");
        Path configFile = world.resolve("liteconfig").resolve("mod").resolve("world-read-failure.json5");
        Files.createDirectories(configFile);

        RegisteredConfig<WorldConfig> registration = RegisteredConfigs.create(
            settings(WorldConfig.class, installation));
        try (ConfigHolder<WorldConfig> holder = registration.access()) {
            registration.worldContext(world, false);

            assertEquals(73, holder.data().value);
            assertTrue(Files.isDirectory(configFile));
        }
    }

    private static Path configPath(Class<?> configType, Path directory) {
        ConfigPathResolver resolver = new ConfigPathResolver(
            configType,
            directory,
            new ConfigScope("mod"),
            new ConfigFileOwnership());
        return resolver.activePath().orElseThrow();
    }

    private static <T> ConfigSettings<T> settings(Class<T> type, Path directory) {
        return new ConfigSettings<>(type, new ConfigScope("mod"), directory.toAbsolutePath().normalize());
    }
}
