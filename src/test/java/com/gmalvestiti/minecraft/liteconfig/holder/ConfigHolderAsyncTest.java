package com.gmalvestiti.minecraft.liteconfig.holder;

import com.gmalvestiti.minecraft.liteconfig.api.ConfigHolder;
import com.gmalvestiti.minecraft.liteconfig.api.LiteConfig;
import com.gmalvestiti.minecraft.liteconfig.api.UpdateResult;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigError;
import com.gmalvestiti.minecraft.liteconfig.exception.LiteConfigException;
import com.gmalvestiti.minecraft.liteconfig.support.TestFixtures;
import com.gmalvestiti.minecraft.liteconfig.support.ConfigRegistryIsolation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(ConfigRegistryIsolation.class)
class ConfigHolderAsyncTest {

    @Test
    void testLoadsSavesAndUpdatesSynchronously(@TempDir Path tempDir) {
        ConfigHolder<TestFixtures.ConfigWithExtension> holder = holder(tempDir);

        assertTrue(holder.update(config -> config.value = 7).accepted());
        holder.save();

        assertTrue(Files.exists(tempDir.resolve("with-extension.json5")));

        holder.update(config -> config.value = 42);
        holder.load();

        assertEquals(7, holder.data().value, "load must restore what save persisted");
    }

    @Test
    void testCompletesAsyncOperationsWithoutBlockingTheCaller(@TempDir Path tempDir) {
        ConfigHolder<TestFixtures.ConfigWithExtension> holder = holder(tempDir);

        CompletableFuture<UpdateResult> pending = holder.updateAndSaveAsync(config -> config.value = 12);

        assertTrue(pending.join().accepted());
        assertEquals(12, holder.data().value);

        holder.saveAsync().join();
        holder.loadAsync().join();
        assertEquals(12, holder.data().value);
    }

    @Test
    void testKeepsPublishedStateIsolatedFromCallerMutation(@TempDir Path tempDir) {
        ConfigHolder<TestFixtures.ConfigWithExtension> holder = holder(tempDir);

        TestFixtures.ConfigWithExtension copy = holder.copy();
        copy.value = 99;

        assertNotSame(holder.data(), copy);
        assertEquals(1, holder.data().value, "mutating a copy must not publish");
    }

    @Test
    void testRejectsUpdatesThatFailValidation(@TempDir Path tempDir) {
        ConfigHolder<TestFixtures.StrictUpdateConfigWithExtension> holder = strictUpdateHolder(tempDir);

        CompletableFuture<UpdateResult> failed = holder.updateAsync(config -> config.value = -1);

        assertThrows(CompletionException.class, failed::join);
        assertTrue(failed.isCompletedExceptionally());

        LiteConfigException failure = assertThrows(
            LiteConfigException.class,
            () -> holder.update(config -> config.value = -1)
        );

        assertEquals(ConfigError.VALIDATION_FAILED, failure.error());
        assertEquals(1, holder.data().value, "a rejected candidate must not reach published state");
    }

    @Test
    void testReportsRejectionsAsAValueUnderFallbackPolicy(@TempDir Path tempDir) {
        ConfigHolder<TestFixtures.ConfigWithExtension> holder = holder(tempDir);

        UpdateResult result = holder.updateAndSave(config -> config.value = -1);

        assertInstanceOf(UpdateResult.Rejected.class, result);
        assertFalse(result.accepted());
        assertEquals(List.of("nonNegative"), result.violations().stream().map(v -> v.id()).toList());
        assertEquals(1, holder.data().value);
    }

    @Test
    void testRefusesNestedSchedulingFromInsideAWorkerTask(@TempDir Path tempDir) {
        ConfigHolder<TestFixtures.ConfigWithExtension> holder = holder(tempDir);
        AtomicReference<CompletableFuture<Void>> nested = new AtomicReference<>();

        holder.updateAsync(config -> {
            config.value = 3;
            nested.set(holder.saveAsync());
        }).join();

        LiteConfigException failure = assertThrows(
            LiteConfigException.class,
            () -> unwrap(nested.get())
        );
        assertEquals(ConfigError.NESTED_CONFIG_OPERATION, failure.error());
        assertEquals(3, holder.data().value, "the outer update must still publish");
    }

    @Test
    void testRejectsSynchronousCallsFromInsideAWorkerTask(@TempDir Path tempDir) {
        ConfigHolder<TestFixtures.ConfigWithExtension> holder = holder(tempDir);

        CompletionException wrapper = assertThrows(CompletionException.class, () ->
            holder.updateAsync(config -> {
                config.value = 4;
                holder.save();
            }).join());

        assertInstanceOf(LiteConfigException.class, wrapper.getCause());
        assertEquals(ConfigError.NESTED_CONFIG_OPERATION,
            ((LiteConfigException) wrapper.getCause()).error());
        assertEquals(1, holder.data().value);
    }

    @Test
    void testRejectsCrossConfigCallsFromInsideAWorkerTask(@TempDir Path tempDir) {
        ConfigHolder<TestFixtures.ConfigWithExtension> first =
            holder(tempDir.resolve("first"));
        ConfigHolder<TestFixtures.SimpleConfig> second =
            LiteConfig.holder(TestFixtures.SimpleConfig.class)
                .modId("mod")
                .baseDir(tempDir.resolve("second"))
                .create();

        CompletionException wrapper = assertThrows(CompletionException.class, () ->
            first.updateAsync(config -> {
                config.value = 4;
                second.save();
            }).join());

        assertInstanceOf(LiteConfigException.class, wrapper.getCause());
        assertEquals(ConfigError.NESTED_CONFIG_OPERATION,
            ((LiteConfigException) wrapper.getCause()).error());
        assertEquals(1, first.data().value);
    }

    @Test
    void testFallsBackAndBacksUpMalformedDataOnLoad(@TempDir Path tempDir) throws Exception {
        ConfigHolder<TestFixtures.ConfigWithExtension> holder = holder(tempDir);
        Path file = tempDir.resolve("with-extension.json5");
        Files.writeString(file, "{");

        holder.load();

        assertEquals(1, holder.data().value);
        assertFalse(Files.exists(file), "a degraded load moves the file aside and keeps defaults in memory");
        try (var entries = Files.list(tempDir)) {
            assertEquals(1, entries.filter(p -> p.getFileName().toString().contains(".corrupt-")).count());
        }
    }

    @Test
    void testThrowsOnMalformedDataUnderStrictReadPolicy(@TempDir Path tempDir) throws Exception {
        ConfigHolder<TestFixtures.StrictConfigWithExtension> holder = strictHolder(tempDir);
        Path file = tempDir.resolve("strict-with-extension.json5");
        Files.writeString(file, "{");

        LiteConfigException failure = assertThrows(LiteConfigException.class, holder::load);

        assertEquals(ConfigError.MALFORMED_CONFIG_DATA, failure.error());
        assertTrue(Files.exists(file));
        try (var entries = Files.list(tempDir)) {
            assertEquals(0, entries.filter(p -> p.getFileName().toString().contains(".corrupt-")).count());
        }
    }

    @Test
    void testRethrowsAnErrorRaisedOnTheWorkerUnchanged(@TempDir Path tempDir) {
        ConfigHolder<TestFixtures.ConfigWithExtension> holder = holder(tempDir);
        Error raised = new Error("worker exploded");

        Error thrown = assertThrows(Error.class, () -> holder.update(config -> {
            throw raised;
        }));

        assertSame(raised, thrown, "an Error must reach the caller untouched, not wrapped");
    }

    @Test
    void testKeepsTheCompletionWrapperForNonRuntimeAsyncFailures(@TempDir Path tempDir) {
        ConfigHolder<TestFixtures.ConfigWithExtension> holder = holder(tempDir);
        IOException raised = new IOException("worker io");

        CompletionException thrown = assertThrows(CompletionException.class, () -> holder.updateAsync(config -> {
            sneakyThrow(raised);
        }).join());

        assertSame(raised, thrown.getCause(),
            "a checked failure cannot be rethrown directly, so the wrapper must carry it");
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneakyThrow(Throwable failure) throws T {
        throw (T) failure;
    }

    private static void unwrap(CompletableFuture<?> future) {
        try {
            future.join();
        } catch (CompletionException ex) {
            throw (RuntimeException) ex.getCause();
        }
    }

    private static ConfigHolder<TestFixtures.ConfigWithExtension> holder(Path tempDir) {
        return LiteConfig.holder(TestFixtures.ConfigWithExtension.class)
            .modId("mod")
            .baseDir(tempDir)
            .create();
    }

    private static ConfigHolder<TestFixtures.StrictUpdateConfigWithExtension> strictUpdateHolder(Path tempDir) {
        return LiteConfig.holder(TestFixtures.StrictUpdateConfigWithExtension.class)
            .modId("mod")
            .baseDir(tempDir)
            .create();
    }

    private static ConfigHolder<TestFixtures.StrictConfigWithExtension> strictHolder(Path tempDir) {
        return LiteConfig.holder(TestFixtures.StrictConfigWithExtension.class)
            .modId("mod")
            .baseDir(tempDir)
            .create();
    }
}
