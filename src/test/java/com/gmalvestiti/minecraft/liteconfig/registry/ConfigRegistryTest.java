package com.gmalvestiti.minecraft.liteconfig.registry;

import com.gmalvestiti.minecraft.liteconfig.api.ConfigHolder;
import com.gmalvestiti.minecraft.liteconfig.api.LiteConfig;
import com.gmalvestiti.minecraft.liteconfig.context.ConfigSettings;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigError;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigScope;
import com.gmalvestiti.minecraft.liteconfig.exception.LiteConfigException;
import com.gmalvestiti.minecraft.liteconfig.support.TestFixtures;
import com.gmalvestiti.minecraft.liteconfig.support.ConfigRegistryIsolation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(ConfigRegistryIsolation.class)
class ConfigRegistryTest {

    @Test
    void testSameConfigClassKeepsIndependentStateAcrossDirectories(@TempDir Path tempDir) {
        Path other = tempDir.resolve("other");

        ConfigHolder<TestFixtures.SimpleConfig> here = holder(tempDir);
        ConfigHolder<TestFixtures.SimpleConfig> there = holder(other);

        here.update(config -> config.value = 42);

        assertEquals(42, here.data().value);
        assertEquals(1, there.data().value);
    }

    @Test
    void testRegistersAConfigOnlyOnce(@TempDir Path tempDir) {
        ConfigHolder<TestFixtures.SimpleConfig> first = holder(tempDir);
        ConfigHolder<TestFixtures.SimpleConfig> second = holder(tempDir);

        first.update(config -> config.value = 42);
        assertEquals(42, second.data().value);
    }

    @Test
    void testReleasesRegistrationAfterFinalHolderCloses(@TempDir Path tempDir) {
        ConfigHolder<TestFixtures.SimpleConfig> first = holder(tempDir);
        ConfigHolder<TestFixtures.SimpleConfig> second = holder(tempDir);
        first.update(config -> config.value = 42);

        first.close();
        assertEquals(42, second.data().value);
        second.close();

        ConfigHolder<TestFixtures.SimpleConfig> reopened = holder(tempDir);
        assertEquals(1, reopened.data().value);
    }

    @Test
    void testRejectsASecondScopeForTheSameRegistration(@TempDir Path tempDir) {
        ConfigRegistry.register(settings(TestFixtures.SimpleConfig.class, "first", tempDir), ignored -> {});

        LiteConfigException failure = assertThrows(
            LiteConfigException.class,
            () -> ConfigRegistry.register(
                settings(TestFixtures.SimpleConfig.class, "second", tempDir),
                ignored -> {})
        );

        assertEquals(ConfigError.CONFLICTING_CONFIG_SCOPE, failure.error());
    }

    @Test
    void testRegistrationHookCanRegisterAnotherConfig(@TempDir Path tempDir) {
        RegisteredConfig<TestFixtures.SimpleConfig> registration = ConfigRegistry.register(
            settings(TestFixtures.SimpleConfig.class, "mod", tempDir),
            ignored -> assertNotNull(ConfigRegistry.register(
                settings(TestFixtures.ConfigWithExtension.class, "mod", tempDir),
                nested -> {}))
        );

        assertNotNull(registration);
    }

    @Test
    void testRejectsRecursiveRegistrationOfTheSameConfig(@TempDir Path tempDir) {
        LiteConfigException failure = assertThrows(
            LiteConfigException.class,
            () -> ConfigRegistry.register(
                settings(TestFixtures.SimpleConfig.class, "mod", tempDir),
                ignored -> ConfigRegistry.register(
                    settings(TestFixtures.SimpleConfig.class, "mod", tempDir),
                    nested -> {}))
        );

        assertEquals(ConfigError.REENTRANT_CONFIG_REGISTRATION, failure.error());
    }

    @Test
    void testRejectsCrossThreadRegistrationCycles(@TempDir Path tempDir) throws Exception {
        ConfigSettings<TestFixtures.SimpleConfig> first =
            settings(TestFixtures.SimpleConfig.class, "mod", tempDir);
        ConfigSettings<TestFixtures.ConfigWithExtension> second =
            settings(TestFixtures.ConfigWithExtension.class, "mod", tempDir);
        CountDownLatch hooksReady = new CountDownLatch(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<LiteConfigException> firstFailure = executor.submit(() -> assertThrows(
                LiteConfigException.class,
                () -> ConfigRegistry.register(first, ignored -> {
                    await(hooksReady);
                    ConfigRegistry.register(second, nested -> {});
                })));
            Future<LiteConfigException> secondFailure = executor.submit(() -> assertThrows(
                LiteConfigException.class,
                () -> ConfigRegistry.register(second, ignored -> {
                    await(hooksReady);
                    ConfigRegistry.register(first, nested -> {});
                })));

            assertEquals(
                ConfigError.REENTRANT_CONFIG_REGISTRATION,
                firstFailure.get(5, TimeUnit.SECONDS).error());
            assertEquals(
                ConfigError.REENTRANT_CONFIG_REGISTRATION,
                secondFailure.get(5, TimeUnit.SECONDS).error());
        } finally {
            executor.shutdownNow();
        }
    }

    private static ConfigHolder<TestFixtures.SimpleConfig> holder(Path baseDirectory) {
        return LiteConfig.holder(TestFixtures.SimpleConfig.class)
            .modId("mod")
            .baseDir(baseDirectory)
            .create();
    }

    private static <T> ConfigSettings<T> settings(
        Class<T> type,
        String modId,
        Path baseDirectory
    ) {
        return new ConfigSettings<>(type, new ConfigScope(modId), baseDirectory);
    }

    private static void await(CountDownLatch latch) {
        latch.countDown();
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Registration hooks did not start together");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }
}
