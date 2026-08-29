package com.gmalvestiti.minecraft.liteconfig.api;

import com.gmalvestiti.minecraft.liteconfig.exception.ConfigError;
import com.gmalvestiti.minecraft.liteconfig.exception.LiteConfigException;
import com.gmalvestiti.minecraft.liteconfig.api.spi.Violation;
import com.gmalvestiti.minecraft.liteconfig.api.spi.StateCloner;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Config;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Entry;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Range;
import com.gmalvestiti.minecraft.liteconfig.support.TestFixtures;
import com.gmalvestiti.minecraft.liteconfig.support.ConfigRegistryIsolation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(ConfigRegistryIsolation.class)
class LiteConfigTest {

    @Test
    void testSupportsLifecycleOperations(@TempDir Path tempDir) {
        ConfigHolder<TestFixtures.ConfigWithExtension> holder = LiteConfig.holder(TestFixtures.ConfigWithExtension.class)
            .modId("mod")
            .baseDir(tempDir.toString())
            .create();

        assertEquals(1, holder.data().value);

        holder.update(cfg -> cfg.value = 4);
        assertEquals(4, holder.data().value);

        holder.updateAndSave(cfg -> cfg.value = 6);
        assertEquals(6, holder.data().value);

        holder.update(cfg -> cfg.value = 2);
        holder.save();

        holder.update(cfg -> cfg.value = 8);
        holder.load();
        assertEquals(2, holder.data().value);
        assertTrue(holder.data().afterLoadCalled);
    }

    @Test
    void testSupportsAsyncLifecycleOperations(@TempDir Path tempDir) {
        ConfigHolder<TestFixtures.ConfigWithExtension> holder = LiteConfig.holder(TestFixtures.ConfigWithExtension.class)
            .modId("mod")
            .baseDir(tempDir.toString())
            .create();

        holder.updateAsync(cfg -> cfg.value = 3).join();
        holder.saveAsync().join();
        holder.updateAsync(cfg -> cfg.value = 9).join();
        holder.loadAsync().join();

        assertEquals(3, holder.data().value);
    }

    @Test
    void testRejectsInvalidDefaultsDuringConstruction(@TempDir Path tempDir) {
        ConfigBuilder<TestFixtures.InvalidDefaultsConfig> builder =
            LiteConfig.holder(TestFixtures.InvalidDefaultsConfig.class)
                .modId("mod")
                .baseDir(tempDir.toString());

        LiteConfigException failure = assertThrows(LiteConfigException.class, builder::create);
        assertEquals(ConfigError.VALIDATION_FAILED, failure.error());
    }

    @Test
    void testRejectsInvalidMutations(@TempDir Path tempDir) {
        ConfigHolder<TestFixtures.StrictUpdateConfigWithExtension> holder =
            LiteConfig.holder(TestFixtures.StrictUpdateConfigWithExtension.class)
            .modId("mod")
            .baseDir(tempDir.toString())
            .create();

        LiteConfigException failure = assertThrows(
            LiteConfigException.class,
            () -> holder.update(cfg -> cfg.value = -1)
        );
        assertEquals(ConfigError.VALIDATION_FAILED, failure.error());
    }

    @Test
    void testReportsAMissingMutatorAsADefect(@TempDir Path tempDir) {
        ConfigHolder<TestFixtures.ConfigWithExtension> holder = holder(tempDir);

        for (Executable blocking : List.<Executable>of(
            () -> holder.updateAndSave(null))) {
            assertEquals(ConfigError.UNEXPECTED_FAILURE, assertThrows(LiteConfigException.class, blocking).error());
        }
        for (Executable async : List.<Executable>of(
            () -> holder.updateAsync(null).join(),
            () -> holder.updateAndSaveAsync(null).join())) {
            assertThrows(CompletionException.class, async);
        }
    }

    @Test
    void testRejectsSynchronousCallsFromTheConfigThread(@TempDir Path tempDir) {
        ConfigHolder<TestFixtures.ConfigWithExtension> holder = holder(tempDir);

        CompletionException wrapper = assertThrows(
            CompletionException.class,
            () -> holder.updateAsync(cfg -> holder.save()).join());

        assertTrue(wrapper.getCause() instanceof LiteConfigException failure
            && failure.error() == ConfigError.NESTED_CONFIG_OPERATION);
    }

    @Test
    void testRejectsNestedSchedulingSoAJoiningHookCannotDeadlockTheWorker(@TempDir Path tempDir) {
        ConfigHolder<TestFixtures.ConfigWithExtension> holder = holder(tempDir);

        CompletionException wrapper = assertThrows(
            CompletionException.class,
            () -> holder.updateAsync(cfg -> holder.saveAsync().join()).join()
        );

        assertTrue(wrapper.getCause() instanceof LiteConfigException failure
            && failure.error() == ConfigError.UNEXPECTED_FAILURE);
        assertTrue(causeChainOf(wrapper).stream().anyMatch(cause ->
            cause instanceof LiteConfigException nested
                && nested.error() == ConfigError.NESTED_CONFIG_OPERATION));
    }

    private static List<Throwable> causeChainOf(Throwable failure) {
        List<Throwable> chain = new ArrayList<>();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            chain.add(current);
            if (current.getCause() == current) {
                break;
            }
        }
        return chain;
    }

    @Test
    void testRestoresDefaultsWhenStoredValuesFailValidation(@TempDir Path tempDir) throws Exception {
        ConfigHolder<TestFixtures.ConfigWithExtension> holder = LiteConfig.holder(TestFixtures.ConfigWithExtension.class)
            .modId("mod")
            .baseDir(tempDir.toString())
            .create();
        holder.save();
        Files.writeString(tempDir.resolve("with-extension.json5"), "{\"value\":-5}");

        holder.load();

        assertEquals(1, holder.data().value);
    }

    @Test
    void testPropagatesStoredValidationFailuresUnderStrictReads(@TempDir Path tempDir) throws Exception {
        ConfigHolder<TestFixtures.StrictReadConfigWithExtension> holder =
            LiteConfig.holder(TestFixtures.StrictReadConfigWithExtension.class)
            .modId("mod")
            .baseDir(tempDir.toString())
            .create();
        holder.save();
        Files.writeString(tempDir.resolve("strict-read-with-extension.json5"), "{\"value\":-5}");

        LiteConfigException failure = assertThrows(LiteConfigException.class, holder::load);
        assertEquals(ConfigError.VALIDATION_FAILED, failure.error());
    }

    @Test
    void testCancelsInvalidUpdatesUnderFallbackPolicy(@TempDir Path tempDir) {
        ConfigHolder<TestFixtures.ConfigWithExtension> holder = LiteConfig.holder(TestFixtures.ConfigWithExtension.class)
            .modId("mod")
            .baseDir(tempDir.toString())
            .create();

        UpdateResult rejected = holder.updateAndSave(cfg -> cfg.value = -1);

        assertEquals(1, holder.data().value);
        assertFalse(rejected.accepted());
        assertEquals(List.of("nonNegative"), rejected.violations().stream().map(Violation::id).toList());
    }

    @Test
    void testReturnsTheViolationsBehindEveryFallbackRejection(@TempDir Path tempDir) {
        ConfigHolder<TestFixtures.ConfigWithExtension> holder = LiteConfig.holder(TestFixtures.ConfigWithExtension.class)
            .modId("mod")
            .baseDir(tempDir.toString())
            .create();

        assertTrue(holder.update(cfg -> cfg.value = 5).accepted());
        assertTrue(holder.updateAndSave(cfg -> cfg.value = 6).accepted());

        for (Supplier<UpdateResult> rejecting : List.<Supplier<UpdateResult>>of(
            () -> holder.update(cfg -> cfg.value = -1),
            () -> holder.updateAndSave(cfg -> cfg.value = -1),
            () -> holder.updateAsync(cfg -> cfg.value = -1).join(),
            () -> holder.updateAndSaveAsync(cfg -> cfg.value = -1).join())) {
            UpdateResult result = rejecting.get();
            assertInstanceOf(UpdateResult.Rejected.class, result);
            List<Violation> rejected = result.violations();
            assertEquals(1, rejected.size());
            assertEquals("nonNegative", rejected.getFirst().id());
            assertEquals("value must be >= 0", rejected.getFirst().message());
        }
        assertEquals(6, holder.data().value);
    }

    @Test
    void testCarriesTheViolationsOnStrictRejections(@TempDir Path tempDir) {
        ConfigHolder<TestFixtures.StrictUpdateConfigWithExtension> holder =
            LiteConfig.holder(TestFixtures.StrictUpdateConfigWithExtension.class)
            .modId("mod")
            .baseDir(tempDir.toString())
            .create();

        LiteConfigException failure = assertThrows(
            LiteConfigException.class,
            () -> holder.update(cfg -> cfg.value = -1)
        );

        assertEquals(ConfigError.VALIDATION_FAILED, failure.error());
        assertEquals(List.of("nonNegative"), failure.violations().stream().map(Violation::id).toList());
        assertThrows(UnsupportedOperationException.class, () -> failure.violations().clear());
    }

    @Test
    void testNeverDegradesABuggyValidatorEvenUnderFallbackPolicy(@TempDir Path tempDir) {
        ConfigHolder<TestFixtures.ThrowingValidatorConfig> holder =
            LiteConfig.holder(TestFixtures.ThrowingValidatorConfig.class)
                .modId("mod")
                .baseDir(tempDir.toString())
                .create();

        LiteConfigException failure = assertThrows(LiteConfigException.class,
            () -> holder.update(cfg -> cfg.value = -1));

        assertEquals(ConfigError.VALIDATOR_FAILED, failure.error());
    }

    @Test
    void testUsesTheConfigsCustomStateCloner(@TempDir Path tempDir) {
        CustomCloner.copies = 0;
        ConfigHolder<CustomClonerConfig> holder = LiteConfig.holder(CustomClonerConfig.class)
            .modId("mod")
            .baseDir(tempDir.toString())
            .create();

        assertEquals(1, holder.data().value);
        assertTrue(CustomCloner.copies > 0);
    }

    @Test
    void testRejectsRootsWithoutConfigAnnotation(@TempDir Path tempDir) {
        LiteConfigException failure = assertThrows(
            LiteConfigException.class,
            () -> LiteConfig.holder(UnannotatedConfig.class)
                .modId("mod")
                .baseDir(tempDir.toString())
                .create()
        );
        assertEquals(ConfigError.MISSING_CONFIG_MARKER, failure.error());
    }

    @Test
    void testInvokesFieldCallbacksOnlyForAcceptedChanges(@TempDir Path tempDir) {
        CallbackConfig.EVENTS.clear();
        ConfigHolder<CallbackConfig> holder = LiteConfig.holder(CallbackConfig.class)
            .modId("mod")
            .baseDir(tempDir)
            .create();

        holder.update(config -> config.value = 4);
        holder.update(config -> config.value = 4);
        UpdateResult rejected = holder.update(config -> config.value = -1);

        assertFalse(rejected.accepted());
        assertEquals(List.of(new CallbackEvent(1, 4, false)), CallbackConfig.EVENTS);
    }

    @Test
    void testInvokesFieldCallbacksForLoad(@TempDir Path tempDir) {
        CallbackConfig.EVENTS.clear();
        ConfigHolder<CallbackConfig> holder = LiteConfig.holder(CallbackConfig.class)
            .modId("mod")
            .baseDir(tempDir)
            .create();

        holder.updateAndSave(config -> config.value = 4);
        holder.update(config -> config.value = 7);
        holder.load();

        assertEquals(
            List.of(
                new CallbackEvent(1, 4, false),
                new CallbackEvent(4, 7, false),
                new CallbackEvent(7, 4, false)
            ),
            CallbackConfig.EVENTS
        );
    }

    @Test
    void testRejectsAnInvalidFieldCallbackDuringHolderConstruction(@TempDir Path tempDir) {
        LiteConfigException failure = assertThrows(
            LiteConfigException.class,
            () -> LiteConfig.holder(InvalidCallbackConfig.class)
                .modId("mod")
                .baseDir(tempDir)
                .create()
        );

        assertEquals(ConfigError.INVALID_ENTRY_ON_SET_CALLBACK, failure.error());
    }

    private static ConfigHolder<TestFixtures.ConfigWithExtension> holder(Path tempDir) {
        return LiteConfig.holder(TestFixtures.ConfigWithExtension.class)
            .modId("mod")
            .baseDir(tempDir.toString())
            .create();
    }

    @Config(name = "custom-cloner", stateCloner = CustomCloner.class)
    public static class CustomClonerConfig {
        public int value = 1;
    }

    public static class CustomCloner implements StateCloner<CustomClonerConfig> {
        private static int copies;

        @Override
        public CustomClonerConfig copy(CustomClonerConfig source) {
            copies++;
            CustomClonerConfig copy = new CustomClonerConfig();
            copy.value = source.value;
            return copy;
        }
    }

    @Config(name = "callbacks")
    public static class CallbackConfig {

        private static final List<CallbackEvent> EVENTS = new ArrayList<>();

        @Entry(callback = "record")
        @Range(min = 0)
        public int value = 1;

        private void record(Integer oldValue, Integer newValue, boolean fromSync) {
            EVENTS.add(new CallbackEvent(oldValue, newValue, fromSync));
        }
    }

    @Config(name = "invalid-callback")
    public static class InvalidCallbackConfig {

        @Entry(callback = "record")
        public int value = 1;

        private void record(int oldValue, int newValue, boolean fromSync) {
        }
    }

    public static class UnannotatedConfig {}

    private record CallbackEvent(int oldValue, int newValue, boolean fromSync) {}
}
