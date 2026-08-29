package com.gmalvestiti.minecraft.liteconfig.registry;

import com.gmalvestiti.minecraft.liteconfig.api.LiteConfig;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Config;
import com.gmalvestiti.minecraft.liteconfig.context.ConfigSettings;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigScope;
import com.gmalvestiti.minecraft.liteconfig.exception.LiteConfigException;
import com.gmalvestiti.minecraft.liteconfig.storage.ConfigFileOwnership;
import com.gmalvestiti.minecraft.liteconfig.support.TestFixtures;
import com.gmalvestiti.minecraft.liteconfig.support.ConfigRegistryIsolation;
import com.gmalvestiti.minecraft.liteconfig.support.RegisteredConfigs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(ConfigRegistryIsolation.class)
class RegisteredConfigTest {

    @Config(name = "transactional")
    static class InvalidTransactionalConfig {
        public TestFixtures.ChildConfig child = new TestFixtures.ChildConfig();
    }

    @Config(name = "transactional")
    static class ValidTransactionalConfig {
        public int value = 1;
    }

    @Test
    void testWiresEveryCollaborator(@TempDir Path tempDir) {
        RegisteredConfig<TestFixtures.ConfigWithExtension> registration = create(
            settings(TestFixtures.ConfigWithExtension.class, tempDir));

        assertSame(TestFixtures.ConfigWithExtension.class, registration.model().type());
        assertEquals("mod", registration.model().scope().modId());
        assertNotNull(registration.engine());
        assertNotNull(registration.guard());
        assertNotNull(registration.notifier());
        assertNotNull(registration.exceptionHandler());
        assertNotNull(registration.state().published());
    }

    @Test
    void testValidatesTheModelBeforeWiring(@TempDir Path tempDir) {
        ConfigSettings<TestFixtures.ParentWithConfigRef> settings =
            settings(TestFixtures.ParentWithConfigRef.class, tempDir);

        assertThrows(LiteConfigException.class, () -> create(settings));
    }

    @Test
    void testRunsValidatorsThroughTheAssembledGuard(@TempDir Path tempDir) {
        RegisteredConfig<TestFixtures.SimpleConfig> registration =
            create(settings(TestFixtures.SimpleConfig.class, tempDir));

        assertTrue(registration.guard().violationsOf(new TestFixtures.SimpleConfig()).isEmpty());
    }

    @Test
    void testFailedRegistrationReleasesItsPathClaim(@TempDir Path tempDir) {
        ConfigFileOwnership ownership = new ConfigFileOwnership();

        assertThrows(
            LiteConfigException.class,
            () -> RegisteredConfigs.create(
                settings(InvalidTransactionalConfig.class, tempDir), ownership));

        RegisteredConfig<ValidTransactionalConfig> registration =
            RegisteredConfigs.create(settings(ValidTransactionalConfig.class, tempDir), ownership);
        assertEquals(1, registration.state().published().value);
    }

    @Test
    void testBuildingThroughThePublicApiProducesAWorkingHolder(@TempDir Path tempDir) {
        assertNotNull(
            LiteConfig.holder(TestFixtures.SimpleConfig.class)
                .modId("mod")
                .baseDir(tempDir.toString())
                .create());
    }

    private static <T> RegisteredConfig<T> create(ConfigSettings<T> settings) {
        return RegisteredConfigs.create(settings);
    }

    private static <T> ConfigSettings<T> settings(Class<T> type, Path tempDir) {
        return new ConfigSettings<>(
            type,
            new ConfigScope("mod"),
            tempDir.toAbsolutePath().normalize()
        );
    }
}
