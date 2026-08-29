package com.gmalvestiti.minecraft.liteconfig.network;

import com.gmalvestiti.minecraft.liteconfig.api.ConfigHolder;
import com.gmalvestiti.minecraft.liteconfig.api.LiteConfig;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Config;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Entry;
import com.gmalvestiti.minecraft.liteconfig.async.ConfigExecutors;
import com.gmalvestiti.minecraft.liteconfig.context.ConfigSettings;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigError;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigScope;
import com.gmalvestiti.minecraft.liteconfig.exception.LiteConfigException;
import com.gmalvestiti.minecraft.liteconfig.holder.ConfigHolderImplementation;
import com.gmalvestiti.minecraft.liteconfig.network.packet.ConfigSyncHandshakeS2CPacket;
import com.gmalvestiti.minecraft.liteconfig.network.packet.ConfigSyncRequestC2SPacket;
import com.gmalvestiti.minecraft.liteconfig.network.packet.ConfigSyncS2CPacket;
import com.gmalvestiti.minecraft.liteconfig.registry.RegisteredConfig;
import com.gmalvestiti.minecraft.liteconfig.support.ConfigRegistryIsolation;
import com.gmalvestiti.minecraft.liteconfig.support.RegisteredConfigs;
import com.gmalvestiti.minecraft.liteconfig.support.TestFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(ConfigRegistryIsolation.class)
class ConfigSyncRegistryTest {

    private static String syncId(Class<?> type) {
        return "mod:" + type.getName();
    }

    @Test
    void testIgnoresAConfigThatAsksForNoSync(@TempDir Path tempDir) {
        ConfigSyncRegistry.register(create(TestFixtures.SimpleConfig.class, tempDir));

        assertTrue(ConfigSyncRegistry.beginHandshake().isEmpty());
    }

    @Test
    void testRejectsOneSyncedTypeFromDifferentRoots(@TempDir Path tempDir) {
        ConfigSyncRegistry.register(create(TestFixtures.SyncedConfig.class, tempDir));

        LiteConfigException failure = assertThrows(
            LiteConfigException.class,
            () -> ConfigSyncRegistry.register(
                create(TestFixtures.SyncedConfig.class, tempDir.resolve("other")))
        );

        assertEquals(ConfigError.CONFLICTING_SYNC_ROOT, failure.error());
    }

    @Test
    void testFailedSyncRegistrationRollsBackConfigAndPathClaim(@TempDir Path tempDir) {
        Path conflictingRoot = tempDir.resolve("other");
        LiteConfig.holder(TestFixtures.SyncedConfig.class)
            .modId("mod")
            .baseDir(tempDir)
            .create();

        assertThrows(
            LiteConfigException.class,
            () -> LiteConfig.holder(TestFixtures.SyncedConfig.class)
                .modId("mod")
                .baseDir(conflictingRoot)
                .create());

        LiteConfig.holder(ReplacementConfig.class)
            .modId("mod")
            .baseDir(conflictingRoot)
            .create();
    }

    @Test
    void testSchedulerFailureRollsBackBothRegistries(@TempDir Path tempDir) {
        ConfigSyncRegistry.setManifestScheduler(() -> {
            throw new IllegalStateException("scheduler failed");
        });

        assertThrows(
            IllegalStateException.class,
            () -> LiteConfig.holder(TestFixtures.SyncedConfig.class)
                .modId("mod")
                .baseDir(tempDir)
                .create());

        assertTrue(ConfigSyncRegistry.beginHandshake().isEmpty());

        ConfigSyncRegistry.setManifestScheduler(() -> {});
        LiteConfig.holder(TestFixtures.SyncedConfig.class)
            .modId("mod")
            .baseDir(tempDir)
            .create();
        assertTrue(handshake().hashes()
            .containsKey(syncId(TestFixtures.SyncedConfig.class)));
    }

    @Test
    void testHandshakeDoesNotLoadConfigsWithoutHolders() {
        assertTrue(ConfigSyncRegistry.beginHandshake().isEmpty());
    }

    @Test
    void testHolderCreationLoadsAndRegistersSyncedConfig(@TempDir Path tempDir) {
        Path configFile = tempDir.resolve("synced.json5");
        assertFalse(Files.exists(configFile));

        LiteConfig.holder(TestFixtures.SyncedConfig.class)
            .modId("mod")
            .baseDir(tempDir)
            .create();

        assertTrue(Files.exists(configFile));
        assertTrue(handshake().hashes()
            .containsKey(syncId(TestFixtures.SyncedConfig.class)));
    }

    @Test
    void testClosingFinalHolderUnregistersSyncedConfig(@TempDir Path tempDir) {
        ConfigHolder<TestFixtures.SyncedConfig> holder = LiteConfig.holder(TestFixtures.SyncedConfig.class)
            .modId("mod")
            .baseDir(tempDir)
            .create();
        assertFalse(ConfigSyncRegistry.beginHandshake().isEmpty());

        holder.close();

        assertTrue(ConfigSyncRegistry.beginHandshake().isEmpty());
    }

    @Test
    void testLateServerHolderBroadcastsANewManifest(@TempDir Path tempDir) {
        List<ConfigSyncHandshakeS2CPacket> manifests = new ArrayList<>();
        ConfigSyncRegistry.setManifestScheduler(
            () -> manifests.add(handshake()));

        LiteConfig.holder(TestFixtures.SyncedConfig.class)
            .modId("mod")
            .baseDir(tempDir)
            .create();

        assertEquals(1, manifests.size());
        assertTrue(manifests.getFirst().hashes()
            .containsKey(syncId(TestFixtures.SyncedConfig.class)));
    }

    @Test
    void testLateHolderRegistrationRequestsItsMismatchedConfig(@TempDir Path tempDir) {
        List<ConfigSyncRequestC2SPacket> requests = new ArrayList<>();
        ConfigSyncRegistry.setRequestScheduler(requests::add);
        ConfigSyncRegistry.receiveHandshake(new ConfigSyncHandshakeS2CPacket(
            Map.of(
                syncId(TestFixtures.SyncedConfig.class),
                ConfigBytes.of(new byte[ConfigSyncProtocol.HASH_BYTES]))));

        LiteConfig.holder(TestFixtures.SyncedConfig.class)
            .modId("mod")
            .baseDir(tempDir)
            .create();

        assertEquals(
            Set.of(syncId(TestFixtures.SyncedConfig.class)),
            requests.getFirst().configIds());
    }

    @Test
    void testMatchingHandshakeNeedsNoPayload(@TempDir Path tempDir) {
        RegisteredConfig<TestFixtures.SyncedConfig> config =
            create(TestFixtures.SyncedConfig.class, tempDir);
        List<ConfigSyncRequestC2SPacket> requests = new ArrayList<>();
        ConfigSyncRegistry.register(config);
        ConfigSyncRegistry.setRequestScheduler(requests::add);

        ConfigSyncRegistry.receiveHandshake(handshake());

        assertTrue(requests.isEmpty());
    }

    @Test
    void testClientRequestsOnlyChangedConfigs(@TempDir Path tempDir) {
        RegisteredConfig<TestFixtures.SyncedConfig> first =
            create(TestFixtures.SyncedConfig.class, tempDir.resolve("first"));
        RegisteredConfig<OtherSyncedConfig> second =
            create(OtherSyncedConfig.class, tempDir.resolve("second"));
        ConfigSyncRegistry.register(first);
        ConfigSyncRegistry.register(second);
        List<ConfigSyncRequestC2SPacket> requests = new ArrayList<>();
        ConfigSyncRegistry.setRequestScheduler(requests::add);
        ConfigSyncHandshakeS2CPacket before = handshake();

        TestFixtures.SyncedConfig changed = first.state().copyOfCanonical();
        changed.maxTeamSize++;
        first.state().replace(changed);
        first.notifier().notifyUpdated(first.state().published());
        ConfigSyncRegistry.receiveHandshake(before);

        assertEquals(
            Set.of(first.model().syncId()),
            requests.getFirst().configIds());
        assertEquals(
            Set.of(first.model().syncId()),
            ConfigSyncRegistry.payloadsFor(requests.getFirst())
                .getFirst().configs().keySet());
    }

    @Test
    void testBroadcastsOnlyWhenSyncedValuesChanged(@TempDir Path tempDir) {
        RegisteredConfig<TestFixtures.SyncedConfig> config =
            create(TestFixtures.SyncedConfig.class, tempDir);
        List<ConfigSyncS2CPacket> broadcasts = new ArrayList<>();
        ConfigSyncRegistry.register(config);
        ConfigSyncRegistry.setBroadcastScheduler(broadcasts::addAll);

        config.notifier().notifyUpdated(config.state().published());

        TestFixtures.SyncedConfig changed = config.state().copyOfCanonical();
        changed.maxTeamSize++;
        config.state().replace(changed);
        config.notifier().notifyUpdated(config.state().published());
        config.notifier().notifyLoaded(config.state().published());

        assertEquals(1, broadcasts.size());
        assertEquals(
            List.of(config.model().syncId()),
            broadcasts.getFirst().configs().keySet().stream().toList());
    }

    @Test
    void testApplyingPayloadDoesNotBroadcastItBack(@TempDir Path tempDir) {
        RegisteredConfig<TestFixtures.SyncedConfig> config =
            create(TestFixtures.SyncedConfig.class, tempDir);
        List<ConfigSyncS2CPacket> broadcasts = new ArrayList<>();
        ConfigSyncRegistry.register(config);
        ConfigSyncRegistry.setBroadcastScheduler(broadcasts::addAll);
        ConfigBytes payload = encodeWith(config, candidate -> candidate.maxTeamSize = 7);

        ConfigSyncRegistry.apply(config.model().syncId(), payload);

        assertEquals(7, config.state().published().maxTeamSize);
        assertTrue(broadcasts.isEmpty());
    }

    @Test
    void testServerValuesAlwaysReplaceMemoryAndFile(@TempDir Path tempDir) throws Exception {
        RegisteredConfig<PartiallySyncedConfig> config =
            create(PartiallySyncedConfig.class, tempDir);
        ConfigSyncRegistry.register(config);
        ConfigHolder<PartiallySyncedConfig> holder =
            new ConfigHolderImplementation<>(config, false);
        holder.updateAndSave(candidate -> candidate.local = "client");
        ConfigBytes payload = encodeWith(config, candidate -> candidate.shared = 9);

        ConfigSyncRegistry.apply(config.model().syncId(), payload);

        assertEquals(9, holder.data().shared);
        assertEquals("client", holder.data().local);
        String file = Files.readString(tempDir.resolve("partial-sync.json5"));
        assertTrue(file.contains("\"shared\": 9"));
        assertTrue(file.contains("\"local\": \"client\""));
    }

    @Test
    void testRestartOnlySyncPersistsThenRequiresDisconnect(@TempDir Path tempDir) throws Exception {
        RegisteredConfig<RestartSyncedConfig> config =
            create(RestartSyncedConfig.class, tempDir);
        ConfigSyncRegistry.register(config);
        ConfigHolder<RestartSyncedConfig> holder =
            new ConfigHolderImplementation<>(config, false);
        ConfigBytes payload = encodeWith(config, candidate -> {
            candidate.poolSize = 32;
            candidate.greeting = "server";
        });

        boolean restartRequired = ConfigSyncRegistry.apply(config.model().syncId(), payload);

        assertTrue(restartRequired);
        assertEquals(8, holder.data().poolSize);
        assertEquals("server", holder.data().greeting);
        String file = Files.readString(tempDir.resolve("restart-sync.json5"));
        assertTrue(file.contains("\"poolSize\": 32"));
        assertTrue(file.contains("\"greeting\": \"server\""));
    }

    @Test
    void testAppliesEntireBatchBeforeRequestingRestart(@TempDir Path tempDir) {
        RegisteredConfig<RestartSyncedConfig> restart =
            create(RestartSyncedConfig.class, tempDir.resolve("restart"));
        RegisteredConfig<OtherSyncedConfig> other =
            create(OtherSyncedConfig.class, tempDir.resolve("other"));
        ConfigSyncRegistry.register(restart);
        ConfigSyncRegistry.register(other);
        ConfigBytes restartPayload = encodeWith(restart, candidate -> candidate.poolSize = 32);
        ConfigBytes otherPayload = encodeWith(other, candidate -> candidate.enabled = false);

        boolean restartRequired = ConfigSyncRegistry.receive(Map.of(
            restart.model().syncId(), restartPayload,
            other.model().syncId(), otherPayload));

        assertTrue(restartRequired);
        assertFalse(other.state().published().enabled);
    }

    @Test
    void testUnknownPayloadIsIgnored() {
        assertFalse(ConfigSyncRegistry.apply("unknown", ConfigBytes.of(new byte[] {1})));
    }

    @Test
    void testServesRequestsWithoutHandshakeState(@TempDir Path tempDir) {
        RegisteredConfig<TestFixtures.SyncedConfig> config =
            create(TestFixtures.SyncedConfig.class, tempDir);
        ConfigSyncRegistry.register(config);
        ConfigSyncRequestC2SPacket request = new ConfigSyncRequestC2SPacket(
            Set.of(config.model().syncId()));

        assertFalse(ConfigSyncRegistry.payloadsFor(request).isEmpty());
        assertFalse(ConfigSyncRegistry.payloadsFor(request).isEmpty());
    }

    @Test
    void testBuffersChunksAndReportsRestartAfterTheLast(@TempDir Path tempDir) {
        RegisteredConfig<RestartSyncedConfig> first =
            create(RestartSyncedConfig.class, tempDir.resolve("first"));
        RegisteredConfig<OtherSyncedConfig> second =
            create(OtherSyncedConfig.class, tempDir.resolve("second"));
        ConfigSyncRegistry.register(first);
        ConfigSyncRegistry.register(second);
        ConfigBytes firstPayload = encodeWith(first, candidate -> {
            candidate.poolSize = 32;
            candidate.greeting = "server";
        });
        ConfigBytes secondPayload = encodeWith(second, candidate -> candidate.enabled = false);

        CompletableFuture<ConfigSyncRegistry.ReceiveResult> firstCompletion =
            ConfigSyncRegistry.receiveResultAsync(
            new ConfigSyncS2CPacket(
                7, false, Map.of(first.model().syncId(), firstPayload)));

        assertFalse(firstCompletion.join().completed());
        assertEquals("hello", first.state().published().greeting);
        assertTrue(second.state().published().enabled);

        ConfigSyncRegistry.ReceiveResult result = ConfigSyncRegistry.receiveResultAsync(
            new ConfigSyncS2CPacket(
                7, true, Map.of(second.model().syncId(), secondPayload))).join();

        assertTrue(result.completed());
        assertTrue(result.restartRequired());
        assertEquals(ConfigSyncRegistry.RESTART_REQUIRED, result.disconnectReason());
        assertEquals("server", first.state().published().greeting);
        assertFalse(second.state().published().enabled);
    }

    @Test
    void testRejectsAnEntireTransactionWhenItsLastChunkIsMalformed(@TempDir Path tempDir) {
        RegisteredConfig<OtherSyncedConfig> first =
            create(OtherSyncedConfig.class, tempDir.resolve("first"));
        RegisteredConfig<RestartSyncedConfig> second =
            create(RestartSyncedConfig.class, tempDir.resolve("second"));
        ConfigSyncRegistry.register(first);
        ConfigSyncRegistry.register(second);
        ConfigBytes firstPayload = encodeWith(first, candidate -> candidate.enabled = false);

        ConfigSyncRegistry.receiveResultAsync(new ConfigSyncS2CPacket(
            8, false, Map.of(first.model().syncId(), firstPayload))).join();
        ConfigSyncRegistry.ReceiveResult result = ConfigSyncRegistry.receiveResultAsync(
            new ConfigSyncS2CPacket(
                8,
                true,
                Map.of(second.model().syncId(), ConfigBytes.of(new byte[] {1, 2, 3}))))
            .join();

        assertTrue(result.completed());
        assertEquals(ConfigSyncRegistry.SYNC_FAILED, result.disconnectReason());
        assertTrue(first.state().published().enabled);
        assertEquals("hello", second.state().published().greeting);
    }

    @Test
    void testKeepsInterleavedTransactionsIndependent(@TempDir Path tempDir) {
        RegisteredConfig<OtherSyncedConfig> first =
            create(OtherSyncedConfig.class, tempDir.resolve("first"));
        RegisteredConfig<RestartSyncedConfig> second =
            create(RestartSyncedConfig.class, tempDir.resolve("second"));
        ConfigSyncRegistry.register(first);
        ConfigSyncRegistry.register(second);
        ConfigBytes firstPayload = encodeWith(first, candidate -> candidate.enabled = false);
        ConfigBytes secondPayload = encodeWith(
            second, candidate -> candidate.greeting = "broadcast");

        ConfigSyncRegistry.receiveResultAsync(new ConfigSyncS2CPacket(
            10, false, Map.of(first.model().syncId(), firstPayload))).join();
        ConfigSyncRegistry.ReceiveResult broadcast = ConfigSyncRegistry.receiveResultAsync(
            new ConfigSyncS2CPacket(
                11, true, Map.of(second.model().syncId(), secondPayload))).join();

        assertTrue(broadcast.completed());
        assertEquals("broadcast", second.state().published().greeting);
        assertTrue(first.state().published().enabled);

        ConfigSyncRegistry.ReceiveResult initial = ConfigSyncRegistry.receiveResultAsync(
            new ConfigSyncS2CPacket(10, true, Map.of())).join();

        assertTrue(initial.completed());
        assertFalse(first.state().published().enabled);
    }

    @Test
    void testRejectsTransactionsWithTooManyPackets() {
        for (int packet = 0; packet < ConfigSyncProtocol.MAX_TRANSACTION_PACKETS; packet++) {
            ConfigSyncRegistry.ReceiveResult pending = ConfigSyncRegistry.receiveResultAsync(
                new ConfigSyncS2CPacket(12, false, Map.of())).join();
            assertFalse(pending.completed());
        }

        ConfigSyncRegistry.ReceiveResult result = ConfigSyncRegistry.receiveResultAsync(
            new ConfigSyncS2CPacket(12, true, Map.of())).join();

        assertTrue(result.completed());
        assertEquals(ConfigSyncRegistry.SYNC_FAILED, result.disconnectReason());
    }

    @Test
    void testDispatchesListenersAfterTheWholeTransactionCommits(@TempDir Path tempDir) {
        RegisteredConfig<OtherSyncedConfig> first =
            create(OtherSyncedConfig.class, tempDir.resolve("first"));
        RegisteredConfig<RestartSyncedConfig> second =
            create(RestartSyncedConfig.class, tempDir.resolve("second"));
        ConfigSyncRegistry.register(first);
        ConfigSyncRegistry.register(second);
        ConfigBytes firstPayload = encodeWith(first, candidate -> candidate.enabled = false);
        ConfigBytes secondPayload = encodeWith(
            second, candidate -> candidate.greeting = "server");

        first.notifier().addUpdateListener(ignored -> {
            RestartSyncedConfig changed = second.state().copyOfCanonical();
            changed.greeting = "local";
            second.state().replace(changed);
        });

        Map<String, ConfigBytes> configs = new java.util.LinkedHashMap<>();
        configs.put(first.model().syncId(), firstPayload);
        configs.put(second.model().syncId(), secondPayload);

        ConfigSyncRegistry.ReceiveResult result = ConfigSyncRegistry.receiveResultAsync(
            new ConfigSyncS2CPacket(14, true, configs)).join();

        assertNull(result.disconnectReason());
        assertFalse(first.state().published().enabled);
        assertEquals("local", second.state().published().greeting);
    }

    @Test
    void testRollsBackEarlierConfigsWhenALaterWriteFails(@TempDir Path tempDir) throws Exception {
        Path firstDir = tempDir.resolve("first");
        Path secondDir = tempDir.resolve("second");
        RegisteredConfig<OtherSyncedConfig> first =
            create(OtherSyncedConfig.class, firstDir);
        RegisteredConfig<RestartSyncedConfig> second =
            create(RestartSyncedConfig.class, secondDir);
        ConfigSyncRegistry.register(first);
        ConfigSyncRegistry.register(second);
        AtomicInteger notifications = new AtomicInteger();
        first.notifier().addUpdateListener(ignored -> notifications.incrementAndGet());

        ConfigBytes firstPayload = encodeWith(first, candidate -> candidate.enabled = false);
        ConfigBytes secondPayload = encodeWith(
            second, candidate -> candidate.greeting = "server");

        Files.delete(secondDir.resolve("restart-sync.json5"));
        Files.delete(secondDir);
        Files.createFile(secondDir);

        Map<String, ConfigBytes> configs = new java.util.LinkedHashMap<>();
        configs.put(first.model().syncId(), firstPayload);
        configs.put(second.model().syncId(), secondPayload);

        ConfigSyncRegistry.ReceiveResult result = ConfigSyncRegistry.receiveResultAsync(
            new ConfigSyncS2CPacket(15, true, configs)).join();

        assertEquals(ConfigSyncRegistry.SYNC_FAILED, result.disconnectReason());
        assertTrue(first.state().published().enabled);
        assertEquals("hello", second.state().published().greeting);
        assertEquals(0, notifications.get());
        assertTrue(Files.readString(firstDir.resolve("other-sync.json5"))
            .contains("\"enabled\": true"));
    }

    @Test
    void testConnectionResetInvalidatesQueuedPayloads(@TempDir Path tempDir) throws Exception {
        RegisteredConfig<OtherSyncedConfig> config =
            create(OtherSyncedConfig.class, tempDir);
        ConfigSyncRegistry.register(config);
        ConfigBytes payload = encodeWith(config, candidate -> candidate.enabled = false);
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);

        ConfigExecutors.defaultExecutor().execute(() -> {
            workerStarted.countDown();
            await(releaseWorker);
        });
        workerStarted.await();

        CompletableFuture<ConfigSyncRegistry.ReceiveResult> queued =
            ConfigSyncRegistry.receiveResultAsync(new ConfigSyncS2CPacket(
                13, true, Map.of(config.model().syncId(), payload)));
        ConfigSyncRegistry.resetClientConnection();
        releaseWorker.countDown();

        ConfigSyncRegistry.ReceiveResult result = queued.join();
        assertTrue(result.completed());
        assertNull(result.disconnectReason());
        assertTrue(config.state().published().enabled);
    }

    @Test
    void testRejectsManifestBeyondConnectionLimit() {
        List<String> disconnects = new ArrayList<>();
        ConfigSyncRegistry.setDisconnectScheduler(disconnects::add);
        ConfigBytes hash = ConfigBytes.of(new byte[ConfigSyncProtocol.HASH_BYTES]);

        for (int batch = 0;
             batch < ConfigSyncProtocol.MAX_MANIFEST_ENTRIES / ConfigSyncProtocol.ENTRIES_PER_PACKET;
             batch++) {

            Map<String, ConfigBytes> hashes = new java.util.LinkedHashMap<>();
            for (int entry = 0; entry < ConfigSyncProtocol.ENTRIES_PER_PACKET; entry++) {
                hashes.put("example.config." + batch + "." + entry, hash);
            }
            ConfigSyncRegistry.receiveHandshake(new ConfigSyncHandshakeS2CPacket(hashes));
        }

        ConfigSyncRegistry.receiveHandshake(new ConfigSyncHandshakeS2CPacket(
            Map.of("example.config.overflow", hash)));

        assertEquals(List.of(ConfigSyncRegistry.SYNC_FAILED), disconnects);
    }

    @Test
    void testRejectsUnsupportedSyncedType(@TempDir Path tempDir) {
        RegisteredConfig<UnsupportedSyncedConfig> config =
            create(UnsupportedSyncedConfig.class, tempDir);

        LiteConfigException failure =
            assertThrows(LiteConfigException.class, () -> ConfigSyncRegistry.register(config));

        assertEquals(ConfigError.UNSUPPORTED_SYNC_TYPE, failure.error());
    }

    private static ConfigSyncHandshakeS2CPacket handshake() {
        return ConfigSyncRegistry.beginHandshake().getFirst();
    }

    private static <T> ConfigBytes encodeWith(RegisteredConfig<T> config, Consumer<T> mutator) {
        T original = config.state().copyOfCanonical();
        T candidate = config.state().copyOfCanonical();
        mutator.accept(candidate);
        config.state().replace(candidate);
        ConfigBytes payload = SyncedConfig.of(config).snapshot().data();
        config.state().replace(original);
        return payload;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    private static <T> RegisteredConfig<T> create(Class<T> type, Path tempDir) {
        ConfigSettings<T> settings = new ConfigSettings<>(
            type,
            new ConfigScope("mod"),
            tempDir.toAbsolutePath().normalize()
        );
        return RegisteredConfigs.create(settings);
    }

    @Config(name = "other-sync", sync = true)
    public static class OtherSyncedConfig {
        public boolean enabled = true;
    }

    @Config(name = "synced")
    public static class ReplacementConfig {
        public int value = 1;
    }

    @Config(name = "partial-sync")
    public static class PartiallySyncedConfig {
        @Entry(sync = true)
        public int shared = 1;
        public String local = "local";
    }

    @Config(name = "restart-sync", sync = true)
    public static class RestartSyncedConfig {
        @Entry(restart = true)
        public int poolSize = 8;
        public String greeting = "hello";
    }

    @Config(name = "unsupported-sync", sync = true)
    public static class UnsupportedSyncedConfig {
        public BigInteger value = BigInteger.ONE;
    }
}
