package com.gmalvestiti.minecraft.liteconfig.engine;

import com.gmalvestiti.minecraft.liteconfig.context.ConfigModel;
import com.gmalvestiti.minecraft.liteconfig.api.spi.StateCloner;
import com.gmalvestiti.minecraft.liteconfig.exception.LiteConfigException;
import com.gmalvestiti.minecraft.liteconfig.storage.ConfigStorage;
import com.gmalvestiti.minecraft.liteconfig.support.TestFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigEngineTest {

    @Test
    void testInitializesDefaults(@TempDir Path tempDir) {
        assertEquals(1, engine(tempDir).initialize().value);
    }

    @Test
    void testLoadsPersistedStateAndRunsAfterLoad(@TempDir Path tempDir) {
        ConfigStorage<TestFixtures.ConfigWithExtension> storage =
            TestFixtures.storage(tempDir, TestFixtures.ConfigWithExtension.class);
        TestFixtures.ConfigWithExtension stored = new TestFixtures.ConfigWithExtension();
        stored.value = 7;
        storage.write(stored);

        TestFixtures.ConfigWithExtension result = engine(tempDir).load();

        assertEquals(7, result.value);
        assertTrue(result.afterLoadCalled);
    }

    @Test
    void testReusesProvidedDefaultsWhenStorageIsEmpty(@TempDir Path tempDir) {
        ConfigEngine<TestFixtures.ConfigWithExtension> engine = engine(tempDir);
        TestFixtures.ConfigWithExtension defaults = engine.initialize();

        TestFixtures.ConfigWithExtension result = engine.load(defaults);

        assertSame(defaults, result);
        assertTrue(result.afterLoadCalled);
    }

    @Test
    void testSavesACopyNormalisedByBeforeSave(@TempDir Path tempDir) {
        TestFixtures.ConfigWithExtension published = new TestFixtures.ConfigWithExtension();
        published.value = 7;

        engine(tempDir).save(published);

        TestFixtures.ConfigWithExtension saved =
            TestFixtures.storage(tempDir, TestFixtures.ConfigWithExtension.class).read();
        assertEquals(7, saved.value);
        assertTrue(saved.beforeSaveCalled);
        assertFalse(published.beforeSaveCalled, "beforeSave must not touch the published state");
    }

    @Test
    void testSavesTheStateDirectlyWhenTheRootHasNoBeforeSaveHook(@TempDir Path tempDir) {
        ConfigModel<TestFixtures.SimpleConfig> model = TestFixtures.model(TestFixtures.SimpleConfig.class);
        ConfigEngine<TestFixtures.SimpleConfig> engine = new ConfigEngine<>(
            model,
            TestFixtures.storage(tempDir, TestFixtures.SimpleConfig.class),
            source -> {
                throw new AssertionError("a root without beforeSave must not be copied");
            });
        TestFixtures.SimpleConfig published = new TestFixtures.SimpleConfig();
        published.value = 7;

        engine.save(published);

        assertEquals(7, TestFixtures.storage(tempDir, TestFixtures.SimpleConfig.class).read().value);
    }

    @Test
    void testCallsDefaultExtensionHooksHarmlessly(@TempDir Path tempDir) {
        ConfigEngine<TestFixtures.BareExtensionConfig> engine = engine(
            tempDir,
            TestFixtures.BareExtensionConfig.class,
            source -> source
        );

        assertEquals(1, engine.load().value);
        engine.save(new TestFixtures.BareExtensionConfig());
    }

    @Test
    void testAttributesHookFailures(@TempDir Path tempDir) {
        ConfigEngine<TestFixtures.HookFailureConfig> engine = engine(
            tempDir,
            TestFixtures.HookFailureConfig.class,
            source -> source
        );

        assertThrows(LiteConfigException.class, engine::load);
        assertThrows(LiteConfigException.class, () -> engine.save(new TestFixtures.HookFailureConfig()));
    }

    @Test
    void testRejectsAliasedCopyBeforeSave(@TempDir Path tempDir) {
        ConfigEngine<TestFixtures.ConfigWithExtension> engine = engine(
            tempDir,
            TestFixtures.ConfigWithExtension.class,
            source -> source
        );

        LiteConfigException failure = assertThrows(LiteConfigException.class,
            () -> engine.save(new TestFixtures.ConfigWithExtension()));

        assertEquals(com.gmalvestiti.minecraft.liteconfig.exception.ConfigError.INVALID_STATE_COPY,
            failure.error());
    }

    private ConfigEngine<TestFixtures.ConfigWithExtension> engine(Path tempDir) {
        ConfigModel<TestFixtures.ConfigWithExtension> model =
            TestFixtures.model(TestFixtures.ConfigWithExtension.class);
        return new ConfigEngine<>(
            model,
            TestFixtures.storage(tempDir, TestFixtures.ConfigWithExtension.class),
            source -> {
                TestFixtures.ConfigWithExtension copy = new TestFixtures.ConfigWithExtension();
                copy.value = source.value;
                copy.afterLoadCalled = source.afterLoadCalled;
                copy.beforeSaveCalled = source.beforeSaveCalled;
                return copy;
            });
    }

    private <T> ConfigEngine<T> engine(Path tempDir, Class<T> type, StateCloner<T> cloner) {
        return new ConfigEngine<>(TestFixtures.model(type), TestFixtures.storage(tempDir, type), cloner);
    }
}
