package com.gmalvestiti.minecraft.liteconfig.holder;

import com.gmalvestiti.minecraft.liteconfig.api.LiteConfig;
import com.gmalvestiti.minecraft.liteconfig.api.ConfigHolder;
import com.gmalvestiti.minecraft.liteconfig.api.UpdateResult;
import com.gmalvestiti.minecraft.liteconfig.api.spi.Violation;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigError;
import com.gmalvestiti.minecraft.liteconfig.exception.LiteConfigException;
import com.gmalvestiti.minecraft.liteconfig.support.TestFixtures;
import com.gmalvestiti.minecraft.liteconfig.support.ConfigRegistryIsolation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(ConfigRegistryIsolation.class)
class ReadOnlyConfigHolderTest {

    @Test
    void testLoadsOnceWhileBuilding(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("with-extension.json5"), "{\"value\":4}");

        ConfigHolder<TestFixtures.ConfigWithExtension> holder = holder(tempDir);

        assertEquals(4, holder.data().value);
        assertTrue(holder.data().afterLoadCalled);
    }

    @Test
    void testFallsBackToDefaultsWhenNoFileExists(@TempDir Path tempDir) {
        ConfigHolder<TestFixtures.ConfigWithExtension> holder = holder(tempDir);

        assertEquals(1, holder.data().value);
    }

    @Test
    void testFallsBackAndBacksUpMalformedDataDuringInitialLoad(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("with-extension.json5");
        Files.writeString(file, "{");

        ConfigHolder<TestFixtures.ConfigWithExtension> holder = holder(tempDir);

        assertEquals(1, holder.data().value);
        assertTrue(Files.readString(file).contains("\"value\": 1"),
            "the restored defaults must be persisted, not just held in memory");
        try (var entries = Files.list(tempDir)) {
            assertEquals(1, entries.filter(p -> p.getFileName().toString().contains(".corrupt-")).count());
        }
    }

    @Test
    void testThrowsWithoutBackingUpMalformedDataDuringInitialStrictLoad(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("strict-with-extension.json5");
        Files.writeString(file, "{");

        LiteConfigException failure = assertThrows(LiteConfigException.class, () -> strictHolder(tempDir));

        assertEquals(ConfigError.MALFORMED_CONFIG_DATA, failure.error());
        assertTrue(Files.exists(file));
        try (var entries = Files.list(tempDir)) {
            assertEquals(0, entries.filter(p -> p.getFileName().toString().contains(".corrupt-")).count());
        }
    }

    @Test
    void testRestoresDefaultsWhenTheLoadedFileFailsValidation(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("with-extension.json5");
        Files.writeString(file, "{\"value\":-5}");

        ConfigHolder<TestFixtures.ConfigWithExtension> holder = holder(tempDir);

        assertEquals(1, holder.data().value, "an invalid file must not become the published state");
        assertTrue(Files.readString(file).contains("\"value\": 1"),
            "the restored defaults must be persisted, not just held in memory");
        try (var entries = Files.list(tempDir)) {
            assertEquals(1, entries.filter(p -> p.getFileName().toString().contains(".corrupt-")).count(),
                "the rejected file must be kept aside so the user can recover their values");
        }
    }

    @Test
    void testPropagatesDefectsFromTheInitialLoadEvenUnderFallback(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("throwing-validator.json5"), "{\"value\":-1}");

        LiteConfigException failure = assertThrows(LiteConfigException.class, () -> LiteConfig
            .holder(TestFixtures.ThrowingValidatorConfig.class)
            .modId("mod")
            .baseDir(tempDir.toString())
            .readOnly()
            .create());

        assertEquals(ConfigError.VALIDATOR_FAILED, failure.error(),
            "a defect must abort construction regardless of the read policy");
    }

    @Test
    void testHandsOutTheSameInstanceOnEveryRead(@TempDir Path tempDir) {
        ConfigHolder<TestFixtures.ConfigWithExtension> holder = holder(tempDir);

        assertSame(holder.data(), holder.data());
    }

    @Test
    void testStillCopiesEvenThoughItRefusesWrites(@TempDir Path tempDir) {
        ConfigHolder<? extends TestFixtures.ConfigWithExtension> holder = strictHolder(tempDir);

        TestFixtures.ConfigWithExtension copy = assertDoesNotThrow(holder::copy);
        copy.value = 42;

        assertNotSame(holder.data(), copy);
        assertEquals(1, holder.data().value);
    }

    @Test
    void testThrowsOnMutatingOperationsUnderStrictPolicies(@TempDir Path tempDir) {
        ConfigHolder<? extends TestFixtures.ConfigWithExtension> holder = strictHolder(tempDir);

        for (Executable refused : List.<Executable>of(
            holder::load,
            () -> holder.update(cfg -> cfg.value = 2),
            () -> holder.updateAndSave(cfg -> cfg.value = 2))) {
            assertEquals(
                ConfigError.HOLDER_OPERATION_UNSUPPORTED,
                assertThrows(LiteConfigException.class, refused).error()
            );
        }
        assertEquals(1, holder.data().value);
    }

    @Test
    void testSkipsMutatingOperationsUnderFallbackPolicies(@TempDir Path tempDir) {
        ConfigHolder<TestFixtures.ConfigWithExtension> holder = LiteConfig
            .holder(TestFixtures.ConfigWithExtension.class)
            .modId("mod")
            .baseDir(tempDir.toString())
            .readOnly()
            .create();

        holder.load();
        UpdateResult update = holder.update(cfg -> cfg.value = 2);
        UpdateResult updateAndSave = holder.updateAndSave(cfg -> cfg.value = 2);

        assertEquals(1, holder.data().value);
        for (UpdateResult result : List.of(update, updateAndSave)) {
            assertInstanceOf(UpdateResult.Rejected.class, result);
            assertEquals(
                List.of("holder.read-only"),
                result.violations().stream().map(Violation::id).toList(),
                "a refusal must explain itself instead of returning an empty rejection");
        }
    }

    @Test
    void testStillSupportsSaving(@TempDir Path tempDir) {
        ConfigHolder<TestFixtures.ConfigWithExtension> holder = holder(tempDir);

        holder.save();

        assertTrue(Files.exists(tempDir.resolve("with-extension.json5")));
    }

    private static ConfigHolder<TestFixtures.StrictConfigWithExtension> strictHolder(Path tempDir) {
        return LiteConfig.holder(TestFixtures.StrictConfigWithExtension.class)
            .modId("mod")
            .baseDir(tempDir.toString())
            .readOnly()
            .create();
    }

    private static ConfigHolder<TestFixtures.ConfigWithExtension> holder(Path tempDir) {
        return LiteConfig.holder(TestFixtures.ConfigWithExtension.class)
            .modId("mod")
            .baseDir(tempDir.toString())
            .readOnly()
            .create();
    }
}
