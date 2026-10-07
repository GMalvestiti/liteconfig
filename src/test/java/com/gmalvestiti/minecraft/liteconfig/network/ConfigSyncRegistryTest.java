package com.gmalvestiti.minecraft.liteconfig.network;

import com.gmalvestiti.minecraft.liteconfig.api.ConfigHolder;
import com.gmalvestiti.minecraft.liteconfig.api.LiteConfig;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Config;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Entry;
import com.gmalvestiti.minecraft.liteconfig.api.ConfigExtension;
import com.gmalvestiti.minecraft.liteconfig.api.spi.Violation;
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
import com.gmalvestiti.minecraft.liteconfig.registry.ConfigRegistry;
import com.gmalvestiti.minecraft.liteconfig.support.TestFixtures;
import com.mojang.serialization.Codec;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(ConfigRegistryIsolation.class)
class ConfigSyncRegistryTest {

    @BeforeEach
    void initializeSync() {
        ConfigSyncRegistry.initialize();
    }

    private static String syncId(Class<?> type) {
        return "mod:" + type.getName();
    }

    @Test
    void testIgnoresAConfigThatAsksForNoSync(@TempDir Path tempDir) {
        create(TestFixtures.SimpleConfig.class, tempDir);

        assertTrue(ServerConfigSync.beginHandshake().isEmpty());
    }

    @Test
    void testRejectsOneSyncedTypeFromDifferentRoots(@TempDir Path tempDir) {
        create(TestFixtures.SyncedConfig.class, tempDir);

        LiteConfigException failure = assertThrows(
            LiteConfigException.class,
            () -> create(TestFixtures.SyncedConfig.class, tempDir.resolve("other"))
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
        ServerConfigSync.setManifestScheduler(() -> {
            throw new IllegalStateException("scheduler failed");
        });

        assertThrows(
            IllegalStateException.class,
            () -> LiteConfig.holder(TestFixtures.SyncedConfig.class)
                .modId("mod")
                .baseDir(tempDir)
                .create());

        assertTrue(ServerConfigSync.beginHandshake().isEmpty());

        ServerConfigSync.setManifestScheduler(() -> {
        });
        LiteConfig.holder(TestFixtures.SyncedConfig.class)
            .modId("mod")
            .baseDir(tempDir)
            .create();
        assertTrue(handshake().hashes()
            .containsKey(syncId(TestFixtures.SyncedConfig.class)));
    }

    @Test
    void testHandshakeDoesNotLoadConfigsWithoutHolders() {
        assertTrue(ServerConfigSync.beginHandshake().isEmpty());
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
        assertFalse(ServerConfigSync.beginHandshake().isEmpty());

        holder.close();

        assertTrue(ServerConfigSync.beginHandshake().isEmpty());
    }

    @Test
    void testLateServerHolderBroadcastsANewManifest(@TempDir Path tempDir) {
        List<ConfigSyncHandshakeS2CPacket> manifests = new ArrayList<>();
        ServerConfigSync.setManifestScheduler(
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
        ClientConfigSync.setRequestScheduler(requests::add);
        ClientConfigSync.receiveHandshake(new ConfigSyncHandshakeS2CPacket(
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
        ClientConfigSync.setRequestScheduler(requests::add);

        ClientConfigSync.receiveHandshake(handshake());

        assertTrue(requests.isEmpty());
    }

    @Test
    void testClientRequestsOnlyChangedConfigs(@TempDir Path tempDir) {
        RegisteredConfig<TestFixtures.SyncedConfig> first =
            create(TestFixtures.SyncedConfig.class, tempDir.resolve("first"));
        RegisteredConfig<OtherSyncedConfig> second =
            create(OtherSyncedConfig.class, tempDir.resolve("second"));
        List<ConfigSyncRequestC2SPacket> requests = new ArrayList<>();
        ClientConfigSync.setRequestScheduler(requests::add);
        ConfigSyncHandshakeS2CPacket before = handshake();

        TestFixtures.SyncedConfig changed = first.state().copyOfCanonical();
        changed.maxTeamSize++;
        first.state().replace(changed);
        first.notifier().notifyUpdated(first.state().published());
        ClientConfigSync.receiveHandshake(before);

        assertEquals(
            Set.of(first.model().syncId()),
            requests.getFirst().configIds());
        assertEquals(
            Set.of(first.model().syncId()),
            ServerConfigSync.payloadsFor(requests.getFirst())
                .getFirst().configs().keySet());
    }

    @Test
    void testBroadcastsOnlyWhenSyncedValuesChanged(@TempDir Path tempDir) {
        RegisteredConfig<TestFixtures.SyncedConfig> config =
            create(TestFixtures.SyncedConfig.class, tempDir);
        List<ConfigSyncS2CPacket> broadcasts = new ArrayList<>();
        ServerConfigSync.setBroadcastScheduler(broadcasts::addAll);

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
        ServerConfigSync.setBroadcastScheduler(broadcasts::addAll);
        ConfigBytes payload = encodeWith(config, candidate -> candidate.maxTeamSize = 7);

        apply(config.model().syncId(), payload);

        assertEquals(7, config.state().published().maxTeamSize);
        assertTrue(broadcasts.isEmpty());
    }

    @Test
    void testServerValuesAlwaysReplaceMemoryAndFile(@TempDir Path tempDir) throws Exception {
        RegisteredConfig<PartiallySyncedConfig> config =
            create(PartiallySyncedConfig.class, tempDir);
        ConfigHolder<PartiallySyncedConfig> holder =
            new ConfigHolderImplementation<>(config, false);
        holder.updateAndSave(candidate -> candidate.local = "client");
        ConfigBytes payload = encodeWith(config, candidate -> candidate.shared = 9);

        apply(config.model().syncId(), payload);

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
        ConfigHolder<RestartSyncedConfig> holder =
            new ConfigHolderImplementation<>(config, false);
        ConfigBytes payload = encodeWith(config, candidate -> {
            candidate.poolSize = 32;
            candidate.greeting = "server";
        });

        boolean restartRequired = apply(config.model().syncId(), payload);

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
        ConfigBytes restartPayload = encodeWith(restart, candidate -> candidate.poolSize = 32);
        ConfigBytes otherPayload = encodeWith(other, candidate -> candidate.enabled = false);

        boolean restartRequired = receive(Map.of(
            restart.model().syncId(), restartPayload,
            other.model().syncId(), otherPayload));

        assertTrue(restartRequired);
        assertFalse(other.state().published().enabled);
    }

    @Test
    void testUnknownPayloadIsIgnored() {
        ClientSyncResult result = ClientConfigSync.receivePayload(
            new ConfigSyncS2CPacket(true, Map.of("unknown", ConfigBytes.of(new byte[]{1}))));

        assertTrue(result.completed());
        assertFalse(result.restartRequired());
        assertNull(result.disconnectReason());
    }

    @Test
    void testServesRequestsWithoutHandshakeState(@TempDir Path tempDir) {
        RegisteredConfig<TestFixtures.SyncedConfig> config =
            create(TestFixtures.SyncedConfig.class, tempDir);
        ConfigSyncRequestC2SPacket request = new ConfigSyncRequestC2SPacket(
            Set.of(config.model().syncId()));

        assertFalse(ServerConfigSync.payloadsFor(request).isEmpty());
        assertFalse(ServerConfigSync.payloadsFor(request).isEmpty());
    }

    @Test
    void testBuffersChunksAndReportsRestartAfterTheLast(@TempDir Path tempDir) {
        RegisteredConfig<RestartSyncedConfig> first =
            create(RestartSyncedConfig.class, tempDir.resolve("first"));
        RegisteredConfig<OtherSyncedConfig> second =
            create(OtherSyncedConfig.class, tempDir.resolve("second"));
        ConfigBytes firstPayload = encodeWith(first, candidate -> {
            candidate.poolSize = 32;
            candidate.greeting = "server";
        });
        ConfigBytes secondPayload = encodeWith(second, candidate -> candidate.enabled = false);

        ClientSyncResult firstCompletion =
            ClientConfigSync.receivePayload(
                new ConfigSyncS2CPacket(
                    7, false, Map.of(first.model().syncId(), firstPayload)));

        assertFalse(firstCompletion.completed());
        assertEquals("hello", first.state().published().greeting);
        assertTrue(second.state().published().enabled);

        ClientSyncResult result = ClientConfigSync.receivePayload(
            new ConfigSyncS2CPacket(
                7, true, Map.of(second.model().syncId(), secondPayload)));

        assertTrue(result.completed());
        assertTrue(result.restartRequired());
        assertEquals(ClientConfigSync.RESTART_REQUIRED, result.disconnectReason());
        assertEquals("server", first.state().published().greeting);
        assertFalse(second.state().published().enabled);
    }

    @Test
    void testRejectsAnEntireTransactionWhenItsLastChunkIsMalformed(@TempDir Path tempDir) {
        RegisteredConfig<OtherSyncedConfig> first =
            create(OtherSyncedConfig.class, tempDir.resolve("first"));
        RegisteredConfig<RestartSyncedConfig> second =
            create(RestartSyncedConfig.class, tempDir.resolve("second"));
        ConfigBytes firstPayload = encodeWith(first, candidate -> candidate.enabled = false);

        ClientConfigSync.receivePayload(new ConfigSyncS2CPacket(
            8, false, Map.of(first.model().syncId(), firstPayload)));
        ClientSyncResult result = ClientConfigSync.receivePayload(
            new ConfigSyncS2CPacket(
                8,
                true,
                Map.of(second.model().syncId(), ConfigBytes.of(new byte[]{1, 2, 3}))));

        assertTrue(result.completed());
        assertEquals(ClientConfigSync.SYNC_FAILED, result.disconnectReason());
        assertTrue(first.state().published().enabled);
        assertEquals("hello", second.state().published().greeting);
    }

    @Test
    void testKeepsInterleavedTransactionsIndependent(@TempDir Path tempDir) {
        RegisteredConfig<OtherSyncedConfig> first =
            create(OtherSyncedConfig.class, tempDir.resolve("first"));
        RegisteredConfig<RestartSyncedConfig> second =
            create(RestartSyncedConfig.class, tempDir.resolve("second"));
        ConfigBytes firstPayload = encodeWith(first, candidate -> candidate.enabled = false);
        ConfigBytes secondPayload = encodeWith(
            second, candidate -> candidate.greeting = "broadcast");

        ClientConfigSync.receivePayload(new ConfigSyncS2CPacket(
            10, false, Map.of(first.model().syncId(), firstPayload)));
        ClientSyncResult broadcast = ClientConfigSync.receivePayload(
            new ConfigSyncS2CPacket(
                11, true, Map.of(second.model().syncId(), secondPayload)));

        assertTrue(broadcast.completed());
        assertEquals("broadcast", second.state().published().greeting);
        assertTrue(first.state().published().enabled);

        ClientSyncResult initial = ClientConfigSync.receivePayload(
            new ConfigSyncS2CPacket(10, true, Map.of()));

        assertTrue(initial.completed());
        assertFalse(first.state().published().enabled);
    }

    @Test
    void testRejectsTransactionsWithTooManyPackets() {
        for (int packet = 0; packet < ConfigSyncProtocol.MAX_TRANSACTION_PACKETS; packet++) {
            ClientSyncResult pending = ClientConfigSync.receivePayload(
                new ConfigSyncS2CPacket(12, false, Map.of()));
            assertFalse(pending.completed());
        }

        ClientSyncResult result = ClientConfigSync.receivePayload(
            new ConfigSyncS2CPacket(12, true, Map.of()));

        assertTrue(result.completed());
        assertEquals(ClientConfigSync.SYNC_FAILED, result.disconnectReason());
    }

    @Test
    void testDispatchesListenersAfterTheWholeTransactionCommits(@TempDir Path tempDir) {
        RegisteredConfig<OtherSyncedConfig> first =
            create(OtherSyncedConfig.class, tempDir.resolve("first"));
        RegisteredConfig<RestartSyncedConfig> second =
            create(RestartSyncedConfig.class, tempDir.resolve("second"));
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

        ClientSyncResult result = ClientConfigSync.receivePayload(
            new ConfigSyncS2CPacket(14, true, configs));

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

        ClientSyncResult result = ClientConfigSync.receivePayload(
            new ConfigSyncS2CPacket(15, true, configs));

        assertEquals(ClientConfigSync.SYNC_FAILED, result.disconnectReason());
        assertTrue(first.state().published().enabled);
        assertEquals("hello", second.state().published().greeting);
        assertEquals(0, notifications.get());
        assertTrue(Files.readString(firstDir.resolve("other-sync.json5"))
            .contains("\"enabled\": true"));
    }

    @Test
    void testConnectionResetDoesNotCancelQueuedPayloads(@TempDir Path tempDir) throws Exception {
        RegisteredConfig<OtherSyncedConfig> config =
            create(OtherSyncedConfig.class, tempDir);
        ConfigBytes payload = encodeWith(config, candidate -> candidate.enabled = false);
        Deque<Runnable> actions = new ArrayDeque<>();
        ClientConfigSync.setClientMainThreadExecutor(actions::addLast);

        List<ClientSyncResult> results = new ArrayList<>();
        actions.addLast(() -> results.add(
            ClientConfigSync.receivePayload(new ConfigSyncS2CPacket(
                13, true, Map.of(config.model().syncId(), payload)))));
        ClientConfigSync.resetClientConnection();
        while (!actions.isEmpty()) {
            actions.removeFirst().run();
        }

        ClientSyncResult result = results.getFirst();
        assertTrue(result.completed());
        assertNull(result.disconnectReason());
        assertFalse(config.state().published().enabled);
    }

    @Test
    void testConnectionResetClearsBufferedTransactions(@TempDir Path tempDir) {
        RegisteredConfig<OtherSyncedConfig> config = create(OtherSyncedConfig.class, tempDir);
        ConfigBytes payload = encodeWith(config, candidate -> candidate.enabled = false);
        ClientSyncResult buffered = ClientConfigSync.receivePayload(
            new ConfigSyncS2CPacket(13, false, Map.of(config.model().syncId(), payload)));
        assertFalse(buffered.completed());

        ClientConfigSync.resetClientConnection();
        ClientSyncResult completed = ClientConfigSync.receivePayload(
            new ConfigSyncS2CPacket(13, true, Map.of()));

        assertTrue(completed.completed());
        assertNull(completed.disconnectReason());
        assertTrue(config.state().published().enabled);
    }

    @Test
    void testLateRegistrationSendsRequestsOnClientThread(@TempDir Path tempDir) throws Exception {
        ClientConfigSync.receiveHandshake(new ConfigSyncHandshakeS2CPacket(Map.of(
            syncId(OtherSyncedConfig.class), ConfigBytes.of(new byte[ConfigSyncProtocol.HASH_BYTES]))));

        List<String> threads = new ArrayList<>();
        ClientConfigSync.setRequestScheduler(ignored -> {
            threads.add(Thread.currentThread().getName());
        });

        Deque<Runnable> actions = new ArrayDeque<>();
        ConfigRegistryIsolation.beforeGameStartup();
        ClientConfigSync.setClientMainThreadExecutor(actions::addLast);
        create(OtherSyncedConfig.class, tempDir);
        assertTrue(threads.isEmpty());
        drain(actions);

        assertEquals(List.of(Thread.currentThread().getName()), threads);
    }

    @Test
    void testConnectionResetDoesNotCancelQueuedRequests(@TempDir Path tempDir) {
        RegisteredConfig<OtherSyncedConfig> config =
            create(OtherSyncedConfig.class, tempDir);
        Deque<Runnable> actions = new ArrayDeque<>();
        List<ConfigSyncRequestC2SPacket> requests = new ArrayList<>();

        ClientConfigSync.setClientMainThreadExecutor(actions::addLast);
        while (!actions.isEmpty()) {
            actions.removeFirst().run();
        }
        ClientConfigSync.setRequestScheduler(requests::add);

        ClientConfigSync.receiveHandshake(new ConfigSyncHandshakeS2CPacket(Map.of(
            config.model().syncId(), ConfigBytes.of(new byte[ConfigSyncProtocol.HASH_BYTES]))));
        assertEquals(1, actions.size());

        ClientConfigSync.resetClientConnection();
        while (!actions.isEmpty()) {
            actions.removeFirst().run();
        }

        assertEquals(1, requests.size());
        assertEquals(Set.of(config.model().syncId()), requests.getFirst().configIds());
    }

    @Test
    void testConnectionResetDoesNotInterruptSave(@TempDir Path tempDir) throws Exception {
        RegisteredConfig<ResetDuringSaveConfig> config = create(ResetDuringSaveConfig.class, tempDir);

        AtomicInteger notifications = new AtomicInteger();
        config.notifier().addUpdateListener(ignored -> notifications.incrementAndGet());
        ConfigBytes payload = encodeWith(config, candidate -> candidate.enabled = false);

        ClientSyncResult result = ClientConfigSync.receivePayload(
            new ConfigSyncS2CPacket(true, Map.of(config.model().syncId(), payload)));

        assertTrue(result.completed());
        assertNull(result.disconnectReason());
        assertFalse(config.state().published().enabled);
        assertEquals(1, notifications.get());
        assertTrue(Files.readString(tempDir.resolve("reset-during-save.json5")).contains("\"enabled\": false"));
    }

    @Test
    void testRejectsManifestBeyondConnectionLimit() {
        List<String> disconnects = new ArrayList<>();
        ClientConfigSync.setDisconnectScheduler(disconnects::add);
        ConfigBytes hash = ConfigBytes.of(new byte[ConfigSyncProtocol.HASH_BYTES]);

        for (int batch = 0;
             batch < ConfigSyncProtocol.MAX_MANIFEST_ENTRIES / ConfigSyncProtocol.ENTRIES_PER_PACKET;
             batch++) {

            Map<String, ConfigBytes> hashes = new java.util.LinkedHashMap<>();
            for (int entry = 0; entry < ConfigSyncProtocol.ENTRIES_PER_PACKET; entry++) {
                hashes.put("example.config." + batch + "." + entry, hash);
            }
            ClientConfigSync.receiveHandshake(new ConfigSyncHandshakeS2CPacket(hashes));
        }

        ClientConfigSync.receiveHandshake(new ConfigSyncHandshakeS2CPacket(
            Map.of("example.config.overflow", hash)));

        assertEquals(List.of(ClientConfigSync.SYNC_FAILED), disconnects);
    }

    @Test
    void testRejectsUnsupportedSyncedType(@TempDir Path tempDir) {
        LiteConfigException failure =
            assertThrows(LiteConfigException.class, () -> create(UnsupportedSyncedConfig.class, tempDir));

        assertEquals(ConfigError.UNSUPPORTED_SYNC_TYPE, failure.error());
    }

    @Test
    void testDefersSyncWorkUntilClientStartup(@TempDir Path tempDir) throws Exception {
        assertStartupActivation(tempDir, ClientConfigSync::setClientMainThreadExecutor);
    }

    @Test
    void testDefersSyncWorkUntilServerStartup(@TempDir Path tempDir) throws Exception {
        assertStartupActivation(tempDir, ServerConfigSync::setServerMainThreadExecutor);
    }

    @Test
    void testRejectsConflictingRootsBeforeStartupWithoutEncoding(@TempDir Path tempDir) throws Exception {
        ConfigRegistryIsolation.beforeGameStartup();
        NetworkThreadConfig.EVENTS.clear();
        ClientConfigSync.setRemoteConnectionCheck(() -> {
            throw new AssertionError("Connection accessed before game startup");
        });
        RegisteredConfig<NetworkThreadConfig> first = createNetworkThreadConfig(tempDir);

        LiteConfigException failure = assertThrows(LiteConfigException.class,
            () -> createNetworkThreadConfig(tempDir.resolve("other")));

        assertEquals(ConfigError.CONFLICTING_SYNC_ROOT, failure.error());
        assertTrue(ConfigSyncRegistry.get(first.model().syncId()).wraps(first));
        assertFalse(ConfigSyncRegistry.get(first.model().syncId()).ready());
        assertTrue(ServerConfigSync.beginHandshake().isEmpty());
        assertTrue(NetworkThreadConfig.EVENTS.stream().noneMatch(event -> event.startsWith("encode:")));
    }

    private static void assertStartupActivation(
        Path directory, Consumer<java.util.concurrent.Executor> bind
    ) throws Exception {
        ConfigRegistryIsolation.beforeGameStartup();
        NetworkThreadConfig.EVENTS.clear();
        ClientConfigSync.setRemoteConnectionCheck(() -> {
            throw new AssertionError("Connection accessed before game startup");
        });
        RegisteredConfig<NetworkThreadConfig> config = createNetworkThreadConfig(directory);

        assertTrue(ConfigSyncRegistry.get(config.model().syncId()).wraps(config));
        assertFalse(ConfigSyncRegistry.get(config.model().syncId()).ready());
        assertTrue(ServerConfigSync.beginHandshake().isEmpty());
        assertFalse(ClientConfigSync.hasRemoteConnection());
        assertTrue(NetworkThreadConfig.EVENTS.stream().noneMatch(event -> event.startsWith("encode:")));
        NetworkThreadConfig.EVENTS.clear();

        bind.accept(Runnable::run);

        assertTrue(ConfigSyncRegistry.get(config.model().syncId()).ready());
        assertFalse(ServerConfigSync.beginHandshake().isEmpty());
        assertEquals(List.of("encode:" + Thread.currentThread().getName()), NetworkThreadConfig.EVENTS);
    }

    @Test
    void testClosingAPendingRegistrationDoesNotActivateIt(@TempDir Path tempDir) throws Exception {
        ConfigRegistryIsolation.beforeGameStartup();

        NetworkThreadConfig.EVENTS.clear();
        RegisteredConfig<NetworkThreadConfig> config = createNetworkThreadConfig(tempDir);
        assertTrue(NetworkThreadConfig.EVENTS.stream().noneMatch(event -> event.startsWith("encode:")));

        NetworkThreadConfig.EVENTS.clear();
        ConfigRegistry.release(config);

        assertNull(ConfigSyncRegistry.get(config.model().syncId()));
        ClientConfigSync.setClientMainThreadExecutor(Runnable::run);

        assertTrue(ServerConfigSync.beginHandshake().isEmpty());
        assertTrue(NetworkThreadConfig.EVENTS.isEmpty());
    }

    @Test
    void testClosingDuringActivationDoesNotResurrectRegistration(@TempDir Path tempDir) throws Exception {
        ConfigRegistryIsolation.beforeGameStartup();
        RegisteredConfig<NetworkThreadConfig> config = createNetworkThreadConfig(tempDir);

        NetworkThreadConfig.BEFORE_ENCODE = () -> ConfigRegistry.release(config);
        try {
            ClientConfigSync.setClientMainThreadExecutor(Runnable::run);

            assertNull(ConfigSyncRegistry.get(config.model().syncId()));
            assertTrue(ServerConfigSync.beginHandshake().isEmpty());
        } finally {
            NetworkThreadConfig.BEFORE_ENCODE = () -> {
            };
        }
    }

    @Test
    void testAllIncomingSyncWorkRunsOnClientMainThread(@TempDir Path tempDir) throws Exception {
        RegisteredConfig<NetworkThreadConfig> config = createNetworkThreadConfig(tempDir);
        ConfigBytes payload = encodeWith(config, candidate -> candidate.value = new ThreadValue(7));
        NetworkThreadConfig.EVENTS.clear();

        ClientSyncResult result = ClientConfigSync.receivePayload(
            new ConfigSyncS2CPacket(true, Map.of(config.model().syncId(), payload)));

        assertNull(result.disconnectReason());
        assertEquals(Set.of("decode", "validate", "save", "encode", "callback"),
            NetworkThreadConfig.EVENTS.stream().map(event -> event.split(":")[0])
                .collect(java.util.stream.Collectors.toSet()));
        assertTrue(NetworkThreadConfig.EVENTS.stream().allMatch(
            event -> event.endsWith(":" + Thread.currentThread().getName())));

        assertEquals(new ThreadValue(7), config.state().published().value);
        assertTrue(Files.readString(tempDir.resolve("network-thread.json5")).contains("7"));
    }

    @Test
    void testBroadcastEncodingAndSendRunOnServerMainThread(@TempDir Path tempDir) throws Exception {
        RegisteredConfig<NetworkThreadConfig> config = createNetworkThreadConfig(tempDir);
        NetworkThreadConfig.EVENTS.clear();

        ServerConfigSync.setBroadcastScheduler(packets -> {
            NetworkThreadConfig.record("send");
        });

        Deque<Runnable> actions = new ArrayDeque<>();
        ServerConfigSync.setServerMainThreadExecutor(actions::addLast);
        drain(actions);

        NetworkThreadConfig changed = config.state().copyOfCanonical();
        changed.value = new ThreadValue(9);
        config.state().replace(changed);
        config.notifier().notifyUpdated(config.state().published());
        assertTrue(NetworkThreadConfig.EVENTS.isEmpty());
        drain(actions);

        String thread = Thread.currentThread().getName();
        assertEquals(List.of("encode:" + thread, "send:" + thread), NetworkThreadConfig.EVENTS);
    }

    @Test
    void testReceivedPayloadDisconnectsOnClientMainThreadAfterCommit(@TempDir Path tempDir) throws Exception {
        RegisteredConfig<RestartSyncedConfig> config = create(RestartSyncedConfig.class, tempDir);
        ConfigBytes payload = encodeWith(config, candidate -> candidate.poolSize = 32);

        List<String> threads = new ArrayList<>();
        List<String> disconnects = new ArrayList<>();
        ClientConfigSync.setDisconnectScheduler(reason -> {
            threads.add(Thread.currentThread().getName());
            disconnects.add(reason);
        });

        ClientSyncResult result = ClientConfigSync.receivePayload(new ConfigSyncS2CPacket(
            true, Map.of(config.model().syncId(), payload)));

        assertEquals(ClientConfigSync.RESTART_REQUIRED, result.disconnectReason());
        assertEquals(List.of(ClientConfigSync.RESTART_REQUIRED), disconnects);
        assertEquals(List.of(Thread.currentThread().getName()), threads);

        assertEquals(8, config.state().published().poolSize);
        assertTrue(Files.readString(tempDir.resolve("restart-sync.json5")).contains("32"));
    }

    @Test
    void testHandshakeRefreshDoesNotSuppressQueuedBroadcast(@TempDir Path tempDir) {
        RegisteredConfig<OtherSyncedConfig> config = create(OtherSyncedConfig.class, tempDir);
        ConfigSyncHandshakeS2CPacket before = handshake();

        OtherSyncedConfig changed = config.state().copyOfCanonical();
        changed.enabled = false;
        config.state().replace(changed);

        Deque<Runnable> actions = new ArrayDeque<>();
        List<ConfigSyncS2CPacket> broadcasts = new ArrayList<>();
        ServerConfigSync.setBroadcastScheduler(broadcasts::addAll);
        ServerConfigSync.setServerMainThreadExecutor(actions::addLast);
        config.notifier().notifyUpdated(config.state().published());

        assertFalse(before.hashes().equals(handshake().hashes()));

        while (!actions.isEmpty()) {
            actions.removeFirst().run();
        }

        assertEquals(1, broadcasts.size());
    }

    @Test
    void testStoppedServerDropsQueuedBroadcastAndManifest(@TempDir Path tempDir) {
        RegisteredConfig<OtherSyncedConfig> config = create(OtherSyncedConfig.class, tempDir);
        Deque<Runnable> actions = new ArrayDeque<>();
        Executor server = actions::addLast;
        List<ConfigSyncS2CPacket> broadcasts = new ArrayList<>();
        AtomicInteger manifests = new AtomicInteger();
        ServerConfigSync.setBroadcastScheduler(broadcasts::addAll);
        ServerConfigSync.setManifestScheduler(manifests::incrementAndGet);
        ServerConfigSync.setServerMainThreadExecutor(server);
        drain(actions);
        manifests.set(0);

        OtherSyncedConfig changed = config.state().copyOfCanonical();
        changed.enabled = false;
        config.state().replace(changed);
        config.notifier().notifyUpdated(config.state().published());
        ServerConfigSync.refreshManifest();
        ServerConfigSync.clearServerMainThreadExecutor(server);
        drain(actions);

        assertTrue(broadcasts.isEmpty());
        assertEquals(0, manifests.get());
    }

    @Test
    void testOldServerCleanupPreservesNewBinding() {
        Executor previous = Runnable::run;
        Executor current = action -> action.run();
        ServerConfigSync.setServerMainThreadExecutor(previous);
        ServerConfigSync.setServerMainThreadExecutor(current);

        ServerConfigSync.clearServerMainThreadExecutor(previous);

        assertTrue(ServerConfigSync.serverMainThreadExecutor() == current);
    }

    @Test
    void testQueuedRequestDropsReleasedRegistration(@TempDir Path tempDir) {
        RegisteredConfig<OtherSyncedConfig> config = create(OtherSyncedConfig.class, tempDir);
        ClientConfigSync.receiveHandshake(new ConfigSyncHandshakeS2CPacket(
            Map.of(config.model().syncId(), ConfigBytes.of(new byte[ConfigSyncProtocol.HASH_BYTES]))));
        Deque<Runnable> actions = new ArrayDeque<>();
        List<ConfigSyncRequestC2SPacket> requests = new ArrayList<>();
        ClientConfigSync.setClientMainThreadExecutor(actions::addLast);
        ClientConfigSync.setRequestScheduler(requests::add);
        ClientConfigSync.requestIfChanged(ConfigSyncRegistry.get(config.model().syncId()));

        ConfigRegistry.release(config);
        drain(actions);

        assertTrue(requests.isEmpty());
    }

    private static RegisteredConfig<NetworkThreadConfig> createNetworkThreadConfig(Path directory) {
        if (LiteConfig.codecs().find(ThreadValue.class).isEmpty()) {
            LiteConfig.codecs()
                .registerCodec(ThreadValue.class, Codec.INT.xmap(ThreadValue::new, ThreadValue::value))
                .registerStreamCodec(ThreadValue.class, new StreamCodec<ByteBuf, ThreadValue>() {
                    @Override
                    public ThreadValue decode(ByteBuf buffer) {
                        NetworkThreadConfig.record("decode");
                        return new ThreadValue(buffer.readInt());
                    }

                    @Override
                    public void encode(ByteBuf buffer, ThreadValue value) {
                        NetworkThreadConfig.BEFORE_ENCODE.run();
                        NetworkThreadConfig.record("encode");
                        buffer.writeInt(value.value());
                    }
                });
        }
        return create(NetworkThreadConfig.class, directory);
    }

    private static ConfigSyncHandshakeS2CPacket handshake() {
        return ServerConfigSync.beginHandshake().getFirst();
    }

    private static boolean apply(String id, ConfigBytes payload) {
        return receive(Map.of(id, payload));
    }

    private static boolean receive(Map<String, ConfigBytes> configs) {
        ClientSyncResult result = ClientConfigSync.receivePayload(
            new ConfigSyncS2CPacket(true, configs));
        assertTrue(result.completed());
        assertEquals(result.restartRequired() ? ClientConfigSync.RESTART_REQUIRED : null,
            result.disconnectReason());
        return result.restartRequired();
    }

    private static <T> ConfigBytes encodeWith(RegisteredConfig<T> config, Consumer<T> mutator) {
        T original = config.state().copyOfCanonical();
        T candidate = config.state().copyOfCanonical();
        mutator.accept(candidate);
        config.state().replace(candidate);
        ConfigBytes payload = SyncedConfig.of(config).cachedSnapshot().data();
        config.state().replace(original);
        return payload;
    }

    private static void drain(Deque<Runnable> actions) {
        while (!actions.isEmpty()) {
            actions.removeFirst().run();
        }
    }

    private static <T> RegisteredConfig<T> create(Class<T> type, Path tempDir) {
        ConfigSettings<T> settings = new ConfigSettings<>(
            type,
            new ConfigScope("mod"),
            tempDir.toAbsolutePath().normalize()
        );
        return ConfigRegistry.register(settings);
    }

    @Config(name = "other-sync", sync = true)
    public static class OtherSyncedConfig {
        public boolean enabled = true;
    }

    public record ThreadValue(int value) {
    }

    @Config(name = "network-thread", sync = true)
    public static class NetworkThreadConfig implements ConfigExtension {
        private static final List<String> EVENTS = new ArrayList<>();
        private static Runnable BEFORE_ENCODE = () -> {
        };

        @Entry(callback = "changed")
        public ThreadValue value = new ThreadValue(1);

        private static void record(String stage) {
            EVENTS.add(stage + ":" + Thread.currentThread().getName());
        }

        @Override
        public void validate(List<Violation> violations) {
            record("validate");
        }

        @Override
        public void beforeSave() {
            record("save");
        }

        private void changed(ThreadValue before, ThreadValue after, boolean fromSync) {
            record("callback");
        }
    }

    @Config(name = "reset-during-save", sync = true)
    public static class ResetDuringSaveConfig implements ConfigExtension {
        public boolean enabled = true;

        @Override
        public void beforeSave() {
            if (!enabled) {
                ClientConfigSync.resetClientConnection();
            }
        }
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
