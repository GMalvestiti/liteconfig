package com.gmalvestiti.minecraft.liteconfig.holder;

import com.gmalvestiti.minecraft.liteconfig.api.ConfigHolder;
import com.gmalvestiti.minecraft.liteconfig.api.LiteConfig;
import com.gmalvestiti.minecraft.liteconfig.api.ConfigSide;
import com.gmalvestiti.minecraft.liteconfig.api.metadata.ConfigMetadata;
import com.gmalvestiti.minecraft.liteconfig.async.ConfigEventExecutors;
import com.gmalvestiti.minecraft.liteconfig.context.ConfigSettings;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigScope;
import com.gmalvestiti.minecraft.liteconfig.exception.LiteConfigException;
import com.gmalvestiti.minecraft.liteconfig.support.ConfigRegistryIsolation;
import com.gmalvestiti.minecraft.liteconfig.support.RegisteredConfigs;
import com.gmalvestiti.minecraft.liteconfig.support.TestFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(ConfigRegistryIsolation.class)
class ConfigHolderImplementationTest {

    @BeforeEach
    void configureClientThread() {
        ConfigEventExecutors.setClientMainThread(Runnable::run);
    }

    @Test
    void testLogsCompletedLoadAndSaveOperationsOnSimpleHolder(@TempDir Path tempDir) {
        ConfigScope scope = spy(new ConfigScope("mod"));
        ConfigHolder<TestFixtures.ConfigWithExtension> holder = holder(tempDir, scope);

        holder.load();
        holder.save();

        verify(scope).logInfo("Config load operation completed successfully");
        verify(scope).logInfo("Config save operation completed successfully");
    }

    @Test
    void testLogsCompletedLoadAndSaveOperationsAsynchronously(@TempDir Path tempDir) {
        ConfigScope scope = spy(new ConfigScope("mod"));
        ConfigHolder<TestFixtures.ConfigWithExtension> holder = holder(tempDir, scope);

        holder.loadAsync().join();
        holder.saveAsync().join();

        verify(scope).logInfo("Config load operation completed successfully");
        verify(scope).logInfo("Config save operation completed successfully");
    }

    @Test
    void testDoesNotLogSuccessWhenTheWritePolicySwallowsAFailedSave(@TempDir Path tempDir) throws Exception {
        Path unusableDir = tempDir.resolve("config");
        Files.createFile(unusableDir);
        ConfigScope scope = spy(new ConfigScope("mod"));
        ConfigHolder<TestFixtures.ConfigWithExtension> holder =
            holder(unusableDir, scope);

        holder.save();

        verify(scope, never()).logInfo("Config save operation completed successfully");
        verify(scope, times(2)).logError(contains("keeping in-memory state"), any(LiteConfigException.class));
    }

    @Test
    void testFailedFallbackWriteDoesNotPublishUpdateCandidate(@TempDir Path tempDir) throws Exception {
        Path unusableDir = tempDir.resolve("config");
        Files.createFile(unusableDir);
        ConfigHolder<TestFixtures.ConfigWithExtension> holder =
            holder(unusableDir, new ConfigScope("mod"));

        var result = holder.updateAndSave(config -> config.value = 9);

        assertFalse(result.accepted());
        assertEquals(1, holder.data().value);
    }

    @Test
    void testCreatesTheConfigFileDuringInitialization(@TempDir Path tempDir) throws Exception {
        ConfigScope scope = spy(new ConfigScope("mod"));

        holder(tempDir, scope);

        assertTrue(Files.readString(tempDir.resolve("with-extension.json5")).contains("\"value\": 1"));
    }

    @Test
    void testAdoptsAnExistingValidFileDuringInitialization(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("with-extension.json5"), "{\"value\":7}");
        ConfigScope scope = spy(new ConfigScope("mod"));

        ConfigHolder<TestFixtures.ConfigWithExtension> holder = holder(tempDir, scope);

        assertEquals(7, holder.data().value, "a valid file must seed the holder without calling load()");
        assertTrue(Files.readString(tempDir.resolve("with-extension.json5")).contains("\"value\": 7"),
            "adopted values must survive the write-back");
    }

    @Test
    void testBacksUpAnInvalidFileAndPersistsDefaultsDuringInitialization(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("with-extension.json5");
        Files.writeString(file, "{\"value\":-5}");
        ConfigScope scope = spy(new ConfigScope("mod"));

        ConfigHolder<TestFixtures.ConfigWithExtension> holder = holder(tempDir, scope);

        assertEquals(1, holder.data().value);
        assertTrue(Files.readString(file).contains("\"value\": 1"));
        try (var entries = Files.list(tempDir)) {
            assertEquals(1, entries.filter(p -> p.getFileName().toString().contains(".corrupt-")).count());
        }
    }

    @Test
    void testPublishesTheSchemaOfTheConfigItHolds(@TempDir Path tempDir) {
        ConfigHolder<TestFixtures.ConstrainedConfig> holder =
            LiteConfig.holder(TestFixtures.ConstrainedConfig.class)
                .modId("mod")
                .baseDir(tempDir.toString())
                .create();

        ConfigMetadata metadata = holder.metadata();

        assertEquals(TestFixtures.ConstrainedConfig.class, metadata.type());
        assertEquals(1, metadata.property("hudScale").orElseThrow().constraints().min().getAsDouble());
        assertTrue(metadata.property("section.max_depth").isPresent());
    }

    @Test
    void testKeepsUpdateAndSaveAtomicAgainstSynchronousUpdates(@TempDir Path tempDir) throws Exception {
        ConfigHolder<TestFixtures.ConfigWithExtension> holder =
            holder(tempDir, new ConfigScope("mod"));
        CountDownLatch updatePublished = new CountDownLatch(1);
        CountDownLatch releaseSave = new CountDownLatch(1);
        holder.onUpdate(ConfigSide.CLIENT, state -> {
            if (state.value == 2) {
                updatePublished.countDown();
                try {
                    releaseSave.await();
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(failure);
                }
            }
        });

        CompletableFuture<?> saving = holder.updateAndSaveAsync(state -> state.value = 2);
        assertTrue(updatePublished.await(5, TimeUnit.SECONDS));
        CompletableFuture<Void> laterUpdate = CompletableFuture.runAsync(
            () -> holder.update(state -> state.value = 3));
        Thread.sleep(50);
        assertFalse(laterUpdate.isDone(), "a synchronous update must queue behind updateAndSave");

        releaseSave.countDown();
        saving.get(5, TimeUnit.SECONDS);
        laterUpdate.get(5, TimeUnit.SECONDS);

        assertEquals(3, holder.data().value);
        assertTrue(Files.readString(tempDir.resolve("with-extension.json5"))
            .contains("\"value\": 2"));
    }

    private static ConfigHolder<TestFixtures.ConfigWithExtension> holder(
        Path tempDir,
        ConfigScope scope
    ) {
        ConfigSettings<TestFixtures.ConfigWithExtension> settings = new ConfigSettings<>(
            TestFixtures.ConfigWithExtension.class,
            scope,
            tempDir.toAbsolutePath().normalize()
        );
        return new ConfigHolderImplementation<>(
            RegisteredConfigs.create(settings), false);
    }
}
