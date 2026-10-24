package com.gmalvestiti.minecraft.liteconfig.network;

import com.gmalvestiti.minecraft.liteconfig.shared.TranslationKey;
import com.gmalvestiti.minecraft.liteconfig.api.ConfigHolder;
import com.gmalvestiti.minecraft.liteconfig.api.ConfigType;
import com.gmalvestiti.minecraft.liteconfig.api.FailurePolicy;
import com.gmalvestiti.minecraft.liteconfig.api.LiteConfig;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Config;
import com.gmalvestiti.minecraft.liteconfig.api.SyncMode;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Entry;
import com.gmalvestiti.minecraft.liteconfig.api.ConfigExtension;
import com.gmalvestiti.minecraft.liteconfig.api.spi.Violation;
import com.gmalvestiti.minecraft.liteconfig.context.ConfigSettings;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigError;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigScope;
import com.gmalvestiti.minecraft.liteconfig.exception.LiteConfigException;
import com.gmalvestiti.minecraft.liteconfig.network.packet.ConfigSyncHandshakeS2CPacket;
import com.gmalvestiti.minecraft.liteconfig.network.packet.ConfigSyncRequestC2SPacket;
import com.gmalvestiti.minecraft.liteconfig.network.packet.ConfigSyncS2CPacket;
import com.gmalvestiti.minecraft.liteconfig.registry.RegisteredConfig;
import com.gmalvestiti.minecraft.liteconfig.support.ConfigRegistryIsolation;
import com.gmalvestiti.minecraft.liteconfig.registry.ConfigRegistry;
import com.gmalvestiti.minecraft.liteconfig.support.TestFixtures;
import com.gmalvestiti.minecraft.liteconfig.support.TestSyncPlatform;
import com.gmalvestiti.minecraft.liteconfig.storage.ConfigFileOwnership;
import com.mojang.serialization.Codec;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
        create(TestFixtures.SimpleConfig.class, tempDir);

        assertTrue(ServerConfigSync.beginHandshake(true).isEmpty());
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
        TestSyncPlatform.setManifestScheduler(() -> {
            throw new IllegalStateException("scheduler failed");
        });

        assertThrows(
            IllegalStateException.class,
            () -> LiteConfig.holder(TestFixtures.SyncedConfig.class)
                .modId("mod")
                .baseDir(tempDir)
                .create());

        assertTrue(ServerConfigSync.beginHandshake(true).isEmpty());

        TestSyncPlatform.setManifestScheduler(() -> {
        });
        LiteConfig.holder(TestFixtures.SyncedConfig.class)
            .modId("mod")
            .baseDir(tempDir)
            .create();
        assertTrue(handshake().hashes()
            .containsKey(syncId(TestFixtures.SyncedConfig.class)));
    }

    @Test
    void testListenerAttachmentFailureRollsBackSyncRegistration(@TempDir Path tempDir) {
        ConfigSettings<TestFixtures.SyncedConfig> settings = new ConfigSettings<>(
            TestFixtures.SyncedConfig.class, new ConfigScope("mod"), tempDir);

        RegisteredConfig<TestFixtures.SyncedConfig> config = RegisteredConfig.create(
            settings, new ConfigFileOwnership(), registration ->
                registration.syncNotifier().setLocalChangeListener(new Object(), () -> {}));
        try {
            assertThrows(IllegalStateException.class, () -> ConfigSyncRegistry.register(config));
            assertNull(ConfigSyncRegistry.get(syncId(TestFixtures.SyncedConfig.class)));
        } finally {
            ConfigRegistry.release(config);
        }
        RegisteredConfig<TestFixtures.SyncedConfig> replacement = ConfigRegistry.register(settings);
        assertTrue(ConfigSyncRegistry.get(replacement.model().syncId()).wraps(replacement));
        ConfigRegistry.release(replacement);
    }

    @Test
    void testHandshakeDoesNotLoadConfigsWithoutHolders() {
        assertTrue(ServerConfigSync.beginHandshake(true).isEmpty());
    }

    @Test
    void testHolderCreationLoadsAndRegistersSyncedConfig(@TempDir Path tempDir) {
        Path configFile = tempDir.resolve("mod").resolve("synced.json5");
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
        assertFalse(ServerConfigSync.beginHandshake(true).isEmpty());

        holder.close();

        assertTrue(ServerConfigSync.beginHandshake(true).isEmpty());
    }

    @Test
    void testLateServerHolderBroadcastsANewManifest(@TempDir Path tempDir) {
        List<ConfigSyncHandshakeS2CPacket> manifests = new ArrayList<>();
        TestSyncPlatform.setManifestScheduler(
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
        TestSyncPlatform.setRequestScheduler(requests::add);
        ClientConfigSync.receiveHandshake(new ConfigSyncHandshakeS2CPacket(
            true,
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
    void testMatchingHandshakeEstablishesServerAuthorityWithoutRequestingPayload(@TempDir Path tempDir) {
        RegisteredConfig<TestFixtures.SyncedConfig> config =
            create(TestFixtures.SyncedConfig.class, tempDir);
        List<ConfigSyncRequestC2SPacket> requests = new ArrayList<>();
        TestSyncPlatform.setRequestScheduler(requests::add);
        AtomicInteger updates = new AtomicInteger();
        config.holderEvents().addUpdateEvent(ignored -> updates.incrementAndGet());

        ClientConfigSync.receiveHandshake(handshake());

        assertTrue(requests.isEmpty());
        assertTrue(config.state().remoteActive());
        assertEquals(0, updates.get());
        ClientConfigSync.resetClientConnection();
        assertFalse(config.state().remoteActive());
        assertTrue(config.state().pending().isEmpty());
        assertEquals(0, updates.get());
    }

    @Test
    void testClientRequestsOnlyHashMismatchesAndAppliesMatchingAuthorityLocally(@TempDir Path tempDir) {
        RegisteredConfig<TestFixtures.SyncedConfig> first =
            create(TestFixtures.SyncedConfig.class, tempDir.resolve("first"));
        RegisteredConfig<OtherSyncedConfig> second =
            create(OtherSyncedConfig.class, tempDir.resolve("second"));
        List<ConfigSyncRequestC2SPacket> requests = new ArrayList<>();
        TestSyncPlatform.setRequestScheduler(requests::add);
        ConfigSyncHandshakeS2CPacket before = handshake();

        TestFixtures.SyncedConfig changed = first.state().copyOfCanonical();
        changed.maxTeamSize++;
        first.state().replace(changed);
        first.syncNotifier().notifyLocalChange();
        ClientConfigSync.receiveHandshake(before);

        assertEquals(
            Set.of(first.model().syncId()),
            requests.getFirst().configIds());
        assertEquals(
            Set.of(first.model().syncId()),
            ServerConfigSync.payloadsFor(requests.getFirst())
                .getFirst().configs().keySet());
        assertTrue(second.state().remoteActive());
    }

    @Test
    void testBroadcastsOnlyWhenSyncedValuesChanged(@TempDir Path tempDir) {
        RegisteredConfig<TestFixtures.SyncedConfig> config =
            create(TestFixtures.SyncedConfig.class, tempDir);
        List<ConfigSyncS2CPacket> broadcasts = new ArrayList<>();
        TestSyncPlatform.setBroadcastScheduler(broadcasts::addAll);

        config.syncNotifier().notifyLocalChange();

        TestFixtures.SyncedConfig changed = config.state().copyOfCanonical();
        changed.maxTeamSize++;
        config.state().replace(changed);
        config.syncNotifier().notifyLocalChange();
        config.syncNotifier().notifyLocalChange();

        assertEquals(1, broadcasts.size());
        assertEquals(
            List.of(config.model().syncId()),
            broadcasts.getFirst().configs().keySet().stream().toList());
    }

    @Test
    void testBroadcastDoesNotEncodeWithoutSyncRecipients(@TempDir Path tempDir) {
        RegisteredConfig<NetworkThreadConfig> config = createNetworkThreadConfig(tempDir);
        NetworkThreadConfig.EVENTS.clear();
        TestSyncPlatform.setSyncRecipientsAvailable(false);

        NetworkThreadConfig changed = config.state().copyOfCanonical();
        changed.value = new ThreadValue(9);
        config.state().replace(changed);
        config.syncNotifier().notifyLocalChange();

        assertTrue(NetworkThreadConfig.EVENTS.isEmpty());
        assertTrue(ServerConfigSync.beginHandshake(true).getFirst().hashes()
            .containsKey(config.model().syncId()));
        assertEquals(List.of("encode:" + Thread.currentThread().getName()), NetworkThreadConfig.EVENTS);
    }

    @Test
    void testApplyingPayloadDoesNotBroadcastItBack(@TempDir Path tempDir) {
        RegisteredConfig<TestFixtures.SyncedConfig> config =
            create(TestFixtures.SyncedConfig.class, tempDir);
        List<ConfigSyncS2CPacket> broadcasts = new ArrayList<>();
        TestSyncPlatform.setBroadcastScheduler(broadcasts::addAll);
        ConfigBytes payload = encodeWith(config, candidate -> candidate.maxTeamSize = 7);

        apply(config.model().syncId(), payload);

        assertEquals(7, config.state().published().maxTeamSize);
        assertTrue(broadcasts.isEmpty());
    }

    @Test
    void testReadOnlyAnnotationStillAllowsInitializationAndRemoteSync(@TempDir Path tempDir)
        throws Exception {
        Path file = tempDir.resolve("mod").resolve("read-only-sync.json5");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"value\":4}");
        RegisteredConfig<ReadOnlySyncedConfig> config = create(ReadOnlySyncedConfig.class, tempDir);
        try (ConfigHolder<ReadOnlySyncedConfig> holder = config.access()) {
            assertEquals(4, holder.data().value);
            assertFalse(holder.update(candidate -> candidate.value = 6).accepted());
            holder.save();
            String originalFile = Files.readString(file);

            apply(config.model().syncId(), encodeWith(config, candidate -> candidate.value = 9));

            assertEquals(9, holder.data().value);
            assertEquals(originalFile, Files.readString(file));
        }
    }

    @Config(name = "read-only-sync", sync = SyncMode.ENABLED, readOnly = true)
    public static class ReadOnlySyncedConfig {
        public int value = 1;
    }

    @Test
    void testServerValuesReplaceEffectiveMemoryWithoutWritingTheLocalFile(@TempDir Path tempDir) throws Exception {
        RegisteredConfig<PartiallySyncedConfig> config =
            create(PartiallySyncedConfig.class, tempDir);
        ConfigHolder<PartiallySyncedConfig> holder =
            config.access();
        holder.updateAndSave(candidate -> candidate.local = "client");
        Path configFile = tempDir.resolve("mod").resolve("partial-sync.json5");
        String originalFile = Files.readString(configFile);
        ConfigBytes payload = encodeWith(config, candidate -> candidate.shared = 9);

        apply(config.model().syncId(), payload);

        assertEquals(9, holder.data().shared);
        assertEquals("client", holder.data().local);
        String file = Files.readString(configFile);
        assertEquals(originalFile, file);
        assertTrue(file.contains("\"shared\": 1"));
        assertTrue(file.contains("\"local\": \"client\""));
        assertTrue(holder.pending().isEmpty());
    }

    @Test
    void testRestartSyncPersistsWireCacheWithoutDisconnecting(@TempDir Path tempDir) throws Exception {
        RegisteredConfig<RestartSyncedConfig> config =
            create(RestartSyncedConfig.class, tempDir);
        ConfigHolder<RestartSyncedConfig> holder =
            config.access();
        Path configFile = tempDir.resolve("mod").resolve("restart-sync.json5");
        String originalFile = Files.readString(configFile);
        List<TranslationKey> disconnects = new ArrayList<>();
        TestSyncPlatform.setDisconnectScheduler(disconnects::add);
        ConfigBytes payload = encodeWith(config, candidate -> {
            candidate.poolSize = 32;
            candidate.greeting = "server";
        });

        boolean restartRequired = apply(config.model().syncId(), payload);

        assertTrue(restartRequired);
        assertEquals(8, holder.data().poolSize);
        assertEquals("server", holder.data().greeting);
        assertEquals(32, holder.pending().orElseThrow().poolSize);
        assertEquals("server", holder.pending().orElseThrow().greeting);
        assertNotEquals(originalFile, Files.readString(configFile));
        assertTrue(Files.readString(configFile).contains("\"poolSize\": 32"));
        assertFalse(Files.readString(configFile).contains("\"greeting\": \"server\""));
        assertTrue(disconnects.isEmpty());
        assertEquals(payload, restartStore(tempDir, config.model().syncId()).read());
        String cacheKey = com.google.common.hash.Hashing.sha256().hashString(
            config.model().syncId() + "\n" + ClientConfigSync.connectionIdentity(),
            java.nio.charset.StandardCharsets.UTF_8).toString();
        Path cacheFile = tempDir.resolve("liteconfig").resolve("servers").resolve(cacheKey + ".bin");
        assertEquals(payload, ConfigBytes.of(Files.readAllBytes(cacheFile)));
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
    void repeatedPendingSyncStillAppliesChangedImmediateValues(@TempDir Path tempDir) {
        RegisteredConfig<RestartSyncedConfig> config = create(RestartSyncedConfig.class, tempDir);
        ConfigHolder<RestartSyncedConfig> holder = config.access();
        List<String> updates = new ArrayList<>();
        holder.onUpdate(value -> updates.add(value.greeting));
        ConfigBytes first = encodeWith(config, candidate -> {
            candidate.poolSize = 32;
            candidate.greeting = "first";
        });
        ConfigBytes second = encodeWith(config, candidate -> {
            candidate.poolSize = 32;
            candidate.greeting = "second";
        });

        assertTrue(apply(config.model().syncId(), first));
        assertTrue(apply(config.model().syncId(), first));
        assertTrue(apply(config.model().syncId(), second));

        assertEquals(8, holder.data().poolSize);
        assertEquals("second", holder.data().greeting);
        assertEquals(32, holder.pending().orElseThrow().poolSize);
        assertEquals("second", holder.pending().orElseThrow().greeting);
        assertEquals(List.of("first", "second"), updates);
    }

    @Test
    void pendingIsAnIsolatedFullDesiredSnapshot(@TempDir Path tempDir) {
        RegisteredConfig<RestartSyncedConfig> config = create(RestartSyncedConfig.class, tempDir);
        ConfigHolder<RestartSyncedConfig> holder = config.access();
        ConfigBytes payload = encodeWith(config, candidate -> {
            candidate.poolSize = 32;
            candidate.greeting = "server";
        });
        assertTrue(apply(config.model().syncId(), payload));
        assertTrue(holder.pending().isPresent());

        RestartSyncedConfig pending = holder.pending().orElseThrow();
        pending.poolSize = 99;
        pending.greeting = "mutated";
        holder.copy().greeting = "copy mutation";

        assertEquals(32, holder.pending().orElseThrow().poolSize);
        assertEquals("server", holder.pending().orElseThrow().greeting);
        assertEquals(8, holder.data().poolSize);
        assertEquals("server", holder.data().greeting);
        assertTrue(apply(config.model().syncId(), payload));
        assertEquals(32, holder.pending().orElseThrow().poolSize);
    }

    @Test
    void restartOnlyChangeSetsPendingWithoutDispatchingEffectiveUpdates(@TempDir Path tempDir) {
        RegisteredConfig<RestartSyncedConfig> config = create(RestartSyncedConfig.class, tempDir);
        ConfigHolder<RestartSyncedConfig> holder = config.access();
        AtomicInteger updates = new AtomicInteger();
        holder.onUpdate(ignored -> updates.incrementAndGet());
        ConfigBytes pending = encodeWith(config, candidate -> candidate.poolSize = 32);
        ConfigBytes current = encodeWith(config, candidate -> candidate.poolSize = 8);

        assertTrue(apply(config.model().syncId(), pending));
        assertTrue(holder.pending().isPresent());
        assertEquals(0, updates.get());
        assertFalse(apply(config.model().syncId(), current));
        assertTrue(holder.pending().isEmpty());
        assertTrue(config.state().remoteActive());
        assertEquals(0, updates.get());
    }

    @Test
    void disconnectRestoresEveryLocalSnapshotAndClearsPending(@TempDir Path tempDir) {
        RegisteredConfig<RestartSyncedConfig> restart =
            create(RestartSyncedConfig.class, tempDir.resolve("restart"));
        RegisteredConfig<PartiallySyncedConfig> partial =
            create(PartiallySyncedConfig.class, tempDir.resolve("partial"));
        ConfigHolder<RestartSyncedConfig> restartHolder = restart.access();
        ConfigHolder<PartiallySyncedConfig> partialHolder = partial.access();
        restartHolder.update(candidate -> candidate.greeting = "local greeting");
        partialHolder.update(candidate -> {
            candidate.shared = 3;
            candidate.local = "player preference";
        });
        List<String> restartUpdates = new ArrayList<>();
        List<Integer> partialUpdates = new ArrayList<>();
        restartHolder.onUpdate(value -> restartUpdates.add(value.greeting));
        partialHolder.onUpdate(value -> partialUpdates.add(value.shared));

        assertTrue(receive(Map.of(
            restart.model().syncId(), encodeWith(restart, candidate -> {
                candidate.poolSize = 32;
                candidate.greeting = "server greeting";
            }),
            partial.model().syncId(), encodeWith(partial, candidate -> candidate.shared = 9))));
        assertEquals("player preference", partialHolder.data().local);

        ClientConfigSync.resetClientConnection();

        assertEquals(8, restartHolder.data().poolSize);
        assertEquals("local greeting", restartHolder.data().greeting);
        assertEquals(32, restartHolder.pending().orElseThrow().poolSize);
        assertFalse(restart.state().remoteActive());
        assertEquals(3, partialHolder.data().shared);
        assertEquals("player preference", partialHolder.data().local);
        assertFalse(partial.state().remoteActive());
        assertEquals(List.of("server greeting", "local greeting"), restartUpdates);
        assertEquals(List.of(9, 3), partialUpdates);
        assertTrue(partialHolder.update(candidate -> candidate.shared = 4).accepted());
    }

    @Test
    void remoteOwnershipRejectsLocalOperationsThroughFallbackPolicies(@TempDir Path tempDir)
        throws Exception {
        RegisteredConfig<PartiallySyncedConfig> config = create(PartiallySyncedConfig.class, tempDir);
        ConfigHolder<PartiallySyncedConfig> holder = config.access();
        assertEquals(ConfigType.SERVER, holder.type());
        assertTrue(holder.isActive());
        Path configFile = holder.path().orElseThrow();
        assertEquals(tempDir.resolve("mod").resolve("partial-sync.json5"), configFile);
        String originalFile = Files.readString(configFile);
        AtomicInteger mutators = new AtomicInteger();
        AtomicInteger loads = new AtomicInteger();
        AtomicInteger saves = new AtomicInteger();
        holder.onLoad(ignored -> loads.incrementAndGet());
        holder.onSave(ignored -> saves.incrementAndGet());
        apply(config.model().syncId(), encodeWith(config, candidate -> candidate.shared = 9));

        assertFalse(holder.update(candidate -> mutators.incrementAndGet()).accepted());
        assertFalse(holder.updateAndSave(candidate -> mutators.incrementAndGet()).accepted());
        holder.load();
        holder.save();

        assertEquals(0, mutators.get());
        assertEquals(0, loads.get());
        assertEquals(0, saves.get());
        assertEquals(9, holder.data().shared);
        assertEquals(originalFile, Files.readString(configFile));
        assertTrue(config.state().remoteActive());

        ClientConfigSync.resetClientConnection();
        assertEquals(1, holder.data().shared);
        assertTrue(holder.pending().isEmpty());
        assertEquals(originalFile, Files.readString(configFile));
        holder.load();
        holder.save();
        assertEquals(1, loads.get());
        assertEquals(1, saves.get());
        assertTrue(holder.update(candidate -> {
            mutators.incrementAndGet();
            candidate.shared = 3;
        }).accepted());
        assertTrue(holder.updateAndSave(candidate -> {
            mutators.incrementAndGet();
            candidate.shared = 4;
        }).accepted());
        assertEquals(2, mutators.get());
        assertEquals(4, holder.data().shared);
        assertEquals(2, saves.get());
        assertTrue(Files.readString(configFile).contains("\"shared\": 4"));
    }

    @Test
    void remoteOwnershipRejectsLocalOperationsThroughStrictPolicies(@TempDir Path tempDir) {
        RegisteredConfig<StrictSyncedConfig> config = create(StrictSyncedConfig.class, tempDir);
        ConfigHolder<StrictSyncedConfig> holder = config.access();
        apply(config.model().syncId(), encodeWith(config, candidate -> candidate.value = 9));

        assertThrows(LiteConfigException.class, () -> holder.update(candidate -> candidate.value = 3));
        assertThrows(LiteConfigException.class, () -> holder.updateAndSave(candidate -> candidate.value = 3));
        assertThrows(LiteConfigException.class, holder::load);
        assertThrows(LiteConfigException.class, holder::save);
        assertEquals(9, holder.data().value);
    }

    @Test
    void clientOwnershipIsExcludedFromHandshakesRequestsAndPayloads(@TempDir Path tempDir) {
        RegisteredConfig<ClientSyncedConfig> config = create(ClientSyncedConfig.class, tempDir);
        List<ConfigSyncRequestC2SPacket> requests = new ArrayList<>();
        TestSyncPlatform.setRequestScheduler(requests::add);

        assertNull(ConfigSyncRegistry.get(config.model().syncId()));
        assertTrue(ServerConfigSync.beginHandshake(true).isEmpty());
        assertTrue(ServerConfigSync.payloadsFor(
            new ConfigSyncRequestC2SPacket(true, Set.of(config.model().syncId()))).isEmpty());
        ClientConfigSync.receiveHandshake(new ConfigSyncHandshakeS2CPacket(true, Map.of(
            config.model().syncId(), ConfigBytes.of(new byte[ConfigSyncProtocol.HASH_BYTES]))));
        apply(config.model().syncId(), encodeWith(config, candidate -> candidate.value = 9));

        assertTrue(requests.isEmpty());
        assertEquals(1, config.state().published().value);
        assertFalse(config.state().remoteActive());
    }

    @Test
    void worldOwnershipIsAdvertisedOnlyWhileALocalWorldIsActive(@TempDir Path tempDir) {
        RegisteredConfig<WorldSyncedConfig> config = create(WorldSyncedConfig.class, tempDir.resolve("config"));
        ConfigHolder<WorldSyncedConfig> holder = config.access();
        assertFalse(holder.isActive());
        assertTrue(holder.path().isEmpty());
        assertTrue(ServerConfigSync.beginHandshake(true).isEmpty());
        assertTrue(ServerConfigSync.payloadsFor(
            new ConfigSyncRequestC2SPacket(true, Set.of(config.model().syncId()))).isEmpty());

        Path world = tempDir.resolve("world");
        ConfigRegistry.activateWorld(world);

        assertTrue(holder.isActive());
        assertEquals(world.resolve("liteconfig").resolve("mod").resolve("world-sync.json5"),
            holder.path().orElseThrow());
        assertTrue(handshake().hashes().containsKey(config.model().syncId()));
        assertFalse(ServerConfigSync.payloadsFor(
            new ConfigSyncRequestC2SPacket(true, Set.of(config.model().syncId()))).isEmpty());

        ConfigRegistry.deactivateWorld();
        assertFalse(holder.isActive());
        assertTrue(holder.path().isEmpty());
        assertTrue(ServerConfigSync.beginHandshake(true).isEmpty());
    }

    @Test
    void remoteWorldAuthorityDoesNotCreateALocalBackingFile(@TempDir Path tempDir) {
        RegisteredConfig<WorldSyncedConfig> config = create(WorldSyncedConfig.class, tempDir);
        ConfigHolder<WorldSyncedConfig> holder = config.access();
        assertEquals(ConfigType.WORLD, holder.type());
        assertFalse(holder.isActive());
        assertTrue(holder.path().isEmpty());
        ConfigBytes payload = encodeWith(config, candidate -> candidate.value = 9);

        apply(config.model().syncId(), payload);

        assertTrue(holder.isActive());
        assertEquals(ConfigType.WORLD, holder.type());
        assertTrue(holder.path().isEmpty());
        assertEquals(9, holder.data().value);
        assertFalse(holder.update(candidate -> candidate.value = 3).accepted());
        assertTrue(ServerConfigSync.beginHandshake(true).isEmpty());
        assertFalse(Files.exists(tempDir.resolve("mod").resolve("world-sync.json5")));
        ClientConfigSync.resetClientConnection();
        assertFalse(holder.isActive());
        assertTrue(holder.path().isEmpty());
        assertTrue(holder.pending().isEmpty());
        assertEquals(1, holder.data().value);
    }

    @Test
    void testUnknownPayloadIsIgnored() {
        ClientSyncResult result = ClientConfigSync.receivePayload(
            new ConfigSyncS2CPacket(0, true, true, Map.of("unknown", ConfigBytes.of(new byte[]{1}))));

        assertTrue(result.completed());
        assertFalse(result.restartRequired());
        assertNull(result.disconnectReason());
    }

    @Test
    void testServesRequestsWithoutHandshakeState(@TempDir Path tempDir) {
        RegisteredConfig<TestFixtures.SyncedConfig> config =
            create(TestFixtures.SyncedConfig.class, tempDir);
        ConfigSyncRequestC2SPacket request = new ConfigSyncRequestC2SPacket(
            true,
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
                    7, false, true, Map.of(first.model().syncId(), firstPayload)));

        assertFalse(firstCompletion.completed());
        assertEquals("hello", first.state().published().greeting);
        assertTrue(second.state().published().enabled);

        ClientSyncResult result = ClientConfigSync.receivePayload(
            new ConfigSyncS2CPacket(
                7, true, true, Map.of(second.model().syncId(), secondPayload)));

        assertTrue(result.completed());
        assertTrue(result.restartRequired());
        assertNull(result.disconnectReason());
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
            8, false, true, Map.of(first.model().syncId(), firstPayload)));
        ClientSyncResult result = ClientConfigSync.receivePayload(
            new ConfigSyncS2CPacket(
                8,
                true,
                true,
                Map.of(second.model().syncId(), ConfigBytes.of(new byte[]{1, 2, 3}))));

        assertTrue(result.completed());
        assertEquals(TranslationKey.SYNC_FAILED, result.disconnectReason());
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
            10, false, true, Map.of(first.model().syncId(), firstPayload)));
        ClientSyncResult broadcast = ClientConfigSync.receivePayload(
            new ConfigSyncS2CPacket(
                11, true, false, Map.of(second.model().syncId(), secondPayload)));

        assertTrue(broadcast.completed());
        assertEquals("broadcast", second.state().published().greeting);
        assertTrue(first.state().published().enabled);

        ClientSyncResult initial = ClientConfigSync.receivePayload(
            new ConfigSyncS2CPacket(10, true, true, Map.of()));

        assertTrue(initial.completed());
        assertFalse(first.state().published().enabled);
    }

    @Test
    void testRejectsTransactionsWithTooManyPackets() {
        for (int packet = 0; packet < ConfigSyncProtocol.MAX_TRANSACTION_PACKETS; packet++) {
            ClientSyncResult pending = ClientConfigSync.receivePayload(
                new ConfigSyncS2CPacket(12, false, true, Map.of()));
            assertFalse(pending.completed());
        }

        ClientSyncResult result = ClientConfigSync.receivePayload(
            new ConfigSyncS2CPacket(12, true, true, Map.of()));

        assertTrue(result.completed());
        assertEquals(TranslationKey.SYNC_FAILED, result.disconnectReason());
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

        first.holderEvents().addUpdateEvent(ignored -> {
            RestartSyncedConfig changed = second.state().copyOfCanonical();
            changed.greeting = "local";
            second.state().replace(changed);
        });

        Map<String, ConfigBytes> configs = new java.util.LinkedHashMap<>();
        configs.put(first.model().syncId(), firstPayload);
        configs.put(second.model().syncId(), secondPayload);

        ClientSyncResult result = ClientConfigSync.receivePayload(
            new ConfigSyncS2CPacket(14, true, true, configs));

        assertNull(result.disconnectReason());
        assertFalse(first.state().published().enabled);
        assertEquals("local", second.state().published().greeting);
    }

    @Test
    void testRollsBackEarlierConfigsWhenALaterWriteFails(@TempDir Path tempDir) throws Exception {
        Path firstDir = tempDir.resolve("first");
        Path secondDir = tempDir.resolve("second");
        RegisteredConfig<NetworkThreadConfig> first = createNetworkThreadConfig(firstDir);
        RegisteredConfig<RestartSyncedConfig> second =
            create(RestartSyncedConfig.class, secondDir);
        AtomicInteger notifications = new AtomicInteger();
        first.holderEvents().addUpdateEvent(ignored -> notifications.incrementAndGet());

        ConfigBytes firstPayload = encodeWith(first, candidate -> candidate.value = new ThreadValue(9));
        ConfigBytes secondPayload = encodeWith(
            second, candidate -> candidate.greeting = "server");

        Map<String, ConfigBytes> configs = new java.util.LinkedHashMap<>();
        configs.put(first.model().syncId(), firstPayload);
        configs.put(second.model().syncId(), secondPayload);

        AtomicInteger committed = new AtomicInteger();
        NetworkThreadConfig.BEFORE_ENCODE = () -> {
            if (first.state().remoteActive()) {
                committed.incrementAndGet();
                blockCacheDirectory(secondDir);
            }
        };
        ClientSyncResult result;
        try {
            result = ClientConfigSync.receivePayload(new ConfigSyncS2CPacket(15, true, true, configs));
        } finally {
            NetworkThreadConfig.BEFORE_ENCODE = () -> {};
        }

        assertEquals(TranslationKey.SYNC_FAILED, result.disconnectReason());
        assertEquals(1, committed.get());
        assertEquals(new ThreadValue(1), first.state().published().value);
        assertFalse(first.state().remoteActive());
        assertEquals("hello", second.state().published().greeting);
        assertEquals(0, notifications.get());
        assertTrue(Files.readString(firstDir.resolve("mod").resolve("network-thread.json5"))
            .contains("1"));
    }

    @Test
    void failedLaterCacheWriteRestoresPriorRemotePendingLocalAndCache(@TempDir Path tempDir)
        throws Exception {
        Path firstDir = tempDir.resolve("first");
        Path secondDir = tempDir.resolve("second");
        RegisteredConfig<RestartSyncedConfig> first = create(RestartSyncedConfig.class, firstDir);
        RegisteredConfig<OtherRestartSyncedConfig> second = create(OtherRestartSyncedConfig.class, secondDir);
        RegisteredConfig<NetworkThreadConfig> witness = createNetworkThreadConfig(tempDir.resolve("witness"));
        ConfigHolder<RestartSyncedConfig> holder = first.access();
        holder.update(candidate -> candidate.greeting = "local");
        ConfigBytes prior = encodeWith(first, candidate -> {
            candidate.poolSize = 16;
            candidate.greeting = "prior remote";
        });
        assertTrue(apply(first.model().syncId(), prior));
        AtomicInteger notifications = new AtomicInteger();
        holder.onUpdate(ignored -> notifications.incrementAndGet());
        Path localFile = firstDir.resolve("mod").resolve("restart-sync.json5");
        String beforeFile = Files.readString(localFile);
        ConfigBytes next = encodeWith(first, candidate -> {
            candidate.poolSize = 32;
            candidate.greeting = "next remote";
        });
        ConfigBytes secondPayload = encodeWith(second, candidate -> candidate.poolSize = 64);
        ConfigBytes witnessPayload = encodeWith(witness, candidate -> candidate.value = new ThreadValue(9));
        Map<String, ConfigBytes> configs = new java.util.LinkedHashMap<>();
        configs.put(witness.model().syncId(), witnessPayload);
        configs.put(first.model().syncId(), next);
        configs.put(second.model().syncId(), secondPayload);

        AtomicInteger committed = new AtomicInteger();
        NetworkThreadConfig.BEFORE_ENCODE = () -> {
            if (witness.state().remoteActive()) {
                committed.incrementAndGet();
                blockCacheDirectory(secondDir);
            }
        };
        ClientSyncResult result;
        try {
            result = ClientConfigSync.receivePayload(new ConfigSyncS2CPacket(16, true, true, configs));
        } finally {
            NetworkThreadConfig.BEFORE_ENCODE = () -> {};
        }

        assertEquals(TranslationKey.SYNC_FAILED, result.disconnectReason());
        assertEquals(1, committed.get());
        assertFalse(witness.state().remoteActive());
        assertEquals(new ThreadValue(1), witness.state().published().value);
        assertTrue(first.state().remoteActive());
        assertEquals(8, holder.data().poolSize);
        assertEquals("prior remote", holder.data().greeting);
        assertEquals(16, holder.pending().orElseThrow().poolSize);
        assertEquals("prior remote", holder.pending().orElseThrow().greeting);
        assertEquals("local", first.state().copyOfLocal().greeting);
        assertEquals(prior, restartStore(firstDir, first.model().syncId()).read());
        assertEquals(beforeFile, Files.readString(localFile));
        assertEquals(0, notifications.get());
        assertFalse(second.state().remoteActive());
        assertEquals(8, second.state().published().poolSize);
        ClientConfigSync.resetClientConnection();
        assertEquals("local", holder.data().greeting);
        assertEquals(16, holder.pending().orElseThrow().poolSize);
    }

    @Test
    void testConnectionResetDoesNotCancelQueuedPayloads(@TempDir Path tempDir) throws Exception {
        RegisteredConfig<OtherSyncedConfig> config =
            create(OtherSyncedConfig.class, tempDir);
        ConfigBytes payload = encodeWith(config, candidate -> candidate.enabled = false);
        Deque<Runnable> actions = new ArrayDeque<>();
        TestSyncPlatform.setClientMainThreadExecutor(actions::addLast);

        List<ClientSyncResult> results = new ArrayList<>();
        actions.addLast(() -> results.add(
            ClientConfigSync.receivePayload(new ConfigSyncS2CPacket(
                13, true, true, Map.of(config.model().syncId(), payload)))));
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
            new ConfigSyncS2CPacket(13, false, true, Map.of(config.model().syncId(), payload)));
        assertFalse(buffered.completed());

        ClientConfigSync.resetClientConnection();
        ClientSyncResult completed = ClientConfigSync.receivePayload(
            new ConfigSyncS2CPacket(13, true, true, Map.of()));

        assertTrue(completed.completed());
        assertNull(completed.disconnectReason());
        assertTrue(config.state().published().enabled);
    }

    @Test
    void connectionResetClearsRememberedServerAuthorityBeforeReactivation(@TempDir Path tempDir) {
        RegisteredConfig<OtherSyncedConfig> config = create(OtherSyncedConfig.class, tempDir);
        List<ConfigSyncRequestC2SPacket> requests = new ArrayList<>();
        TestSyncPlatform.setRequestScheduler(requests::add);
        ClientConfigSync.receiveHandshake(new ConfigSyncHandshakeS2CPacket(true, Map.of(
            config.model().syncId(), ConfigBytes.of(new byte[ConfigSyncProtocol.HASH_BYTES]))));
        assertEquals(1, requests.size());

        ClientConfigSync.resetClientConnection();
        requests.clear();
        ClientConfigSync.requestIfAdvertised(ConfigSyncRegistry.get(config.model().syncId()));

        assertTrue(requests.isEmpty());
    }

    @Test
    void testLateRegistrationSendsRequestsOnClientThread(@TempDir Path tempDir) throws Exception {
        ClientConfigSync.receiveHandshake(new ConfigSyncHandshakeS2CPacket(true, Map.of(
            syncId(OtherSyncedConfig.class), ConfigBytes.of(new byte[ConfigSyncProtocol.HASH_BYTES]))));

        List<String> threads = new ArrayList<>();
        TestSyncPlatform.setRequestScheduler(ignored -> {
            threads.add(Thread.currentThread().getName());
        });

        Deque<Runnable> actions = new ArrayDeque<>();
        ConfigRegistryIsolation.beforeGameStartup();
        TestSyncPlatform.setClientMainThreadExecutor(actions::addLast);
        create(OtherSyncedConfig.class, tempDir);
        assertTrue(threads.isEmpty());
        drain(actions);

        assertEquals(List.of(Thread.currentThread().getName()), threads);
    }

    @Test
    void testHandshakeRequestsAreSynchronousAndResetDoesNotUndoThem(@TempDir Path tempDir) {
        RegisteredConfig<OtherSyncedConfig> config =
            create(OtherSyncedConfig.class, tempDir);
        Deque<Runnable> actions = new ArrayDeque<>();
        List<ConfigSyncRequestC2SPacket> requests = new ArrayList<>();

        TestSyncPlatform.setClientMainThreadExecutor(actions::addLast);
        while (!actions.isEmpty()) {
            actions.removeFirst().run();
        }
        TestSyncPlatform.setRequestScheduler(requests::add);

        ClientConfigSync.receiveHandshake(new ConfigSyncHandshakeS2CPacket(true, Map.of(
            config.model().syncId(), ConfigBytes.of(new byte[ConfigSyncProtocol.HASH_BYTES]))));
        assertTrue(actions.isEmpty());
        assertEquals(1, requests.size());

        ClientConfigSync.resetClientConnection();
        while (!actions.isEmpty()) {
            actions.removeFirst().run();
        }

        assertEquals(1, requests.size());
        assertEquals(Set.of(config.model().syncId()), requests.getFirst().configIds());
    }

    @Test
    void testRemoteSyncDoesNotInvokeSaveHooksOrSaveEvents(@TempDir Path tempDir) throws Exception {
        RegisteredConfig<ResetDuringSaveConfig> config = create(ResetDuringSaveConfig.class, tempDir);

        AtomicInteger notifications = new AtomicInteger();
        config.holderEvents().addUpdateEvent(ignored -> notifications.incrementAndGet());
        AtomicInteger saves = new AtomicInteger();
        config.holderEvents().addSaveEvent(ignored -> saves.incrementAndGet());
        Path configFile = tempDir.resolve("mod").resolve("reset-during-save.json5");
        String originalFile = Files.readString(configFile);
        ConfigBytes payload = encodeWith(config, candidate -> candidate.enabled = false);

        ClientSyncResult result = ClientConfigSync.receivePayload(
            new ConfigSyncS2CPacket(0, true, true, Map.of(config.model().syncId(), payload)));

        assertTrue(result.completed());
        assertNull(result.disconnectReason());
        assertFalse(config.state().published().enabled);
        assertEquals(1, notifications.get());
        assertEquals(0, saves.get());
        assertEquals(originalFile, Files.readString(configFile));
    }

    @Test
    void testRejectsManifestBeyondConnectionLimit() {
        List<TranslationKey> disconnects = new ArrayList<>();
        TestSyncPlatform.setDisconnectScheduler(disconnects::add);
        ConfigBytes hash = ConfigBytes.of(new byte[ConfigSyncProtocol.HASH_BYTES]);

        for (int batch = 0;
             batch < ConfigSyncProtocol.MAX_MANIFEST_ENTRIES / ConfigSyncProtocol.ENTRIES_PER_PACKET;
             batch++) {

            Map<String, ConfigBytes> hashes = new java.util.LinkedHashMap<>();
            for (int entry = 0; entry < ConfigSyncProtocol.ENTRIES_PER_PACKET; entry++) {
                hashes.put("example.config." + batch + "." + entry, hash);
            }
            ClientConfigSync.receiveHandshake(new ConfigSyncHandshakeS2CPacket(true, hashes));
        }

        ClientConfigSync.receiveHandshake(new ConfigSyncHandshakeS2CPacket(
            true,
            Map.of("example.config.overflow", hash)));

        assertEquals(List.of(TranslationKey.SYNC_FAILED), disconnects);
    }

    @Test
    void testRejectsUnsupportedSyncedType(@TempDir Path tempDir) {
        LiteConfigException failure =
            assertThrows(LiteConfigException.class, () -> create(UnsupportedSyncedConfig.class, tempDir));

        assertEquals(ConfigError.UNSUPPORTED_SYNC_TYPE, failure.error());
    }

    @Test
    void testDefersSyncWorkUntilClientStartup(@TempDir Path tempDir) throws Exception {
        assertStartupActivation(tempDir, TestSyncPlatform::setClientMainThreadExecutor);
    }

    @Test
    void testDefersSyncWorkUntilServerStartup(@TempDir Path tempDir) throws Exception {
        assertStartupActivation(tempDir, TestSyncPlatform::setServerMainThreadExecutor);
    }

    @Test
    void testRejectsConflictingRootsBeforeStartupWithoutEncoding(@TempDir Path tempDir) throws Exception {
        ConfigRegistryIsolation.beforeGameStartup();
        NetworkThreadConfig.EVENTS.clear();
        TestSyncPlatform.setRemoteConnectionCheck(() -> {
            throw new AssertionError("Connection accessed before game startup");
        });
        RegisteredConfig<NetworkThreadConfig> first = createNetworkThreadConfig(tempDir);

        LiteConfigException failure = assertThrows(LiteConfigException.class,
            () -> createNetworkThreadConfig(tempDir.resolve("other")));

        assertEquals(ConfigError.CONFLICTING_SYNC_ROOT, failure.error());
        assertTrue(ConfigSyncRegistry.get(first.model().syncId()).wraps(first));
        assertFalse(ConfigSyncRegistry.get(first.model().syncId()).ready());
        assertTrue(ServerConfigSync.beginHandshake(true).isEmpty());
        assertTrue(NetworkThreadConfig.EVENTS.stream().noneMatch(event -> event.startsWith("encode:")));
    }

    private static void assertStartupActivation(
        Path directory, Consumer<java.util.concurrent.Executor> bind
    ) throws Exception {
        ConfigRegistryIsolation.beforeGameStartup();
        NetworkThreadConfig.EVENTS.clear();
        TestSyncPlatform.setRemoteConnectionCheck(() -> {
            throw new AssertionError("Connection accessed before game startup");
        });
        RegisteredConfig<NetworkThreadConfig> config = createNetworkThreadConfig(directory);

        assertTrue(ConfigSyncRegistry.get(config.model().syncId()).wraps(config));
        assertFalse(ConfigSyncRegistry.get(config.model().syncId()).ready());
        assertTrue(ServerConfigSync.beginHandshake(true).isEmpty());
        assertFalse(ClientConfigSync.hasRemoteConnection());
        assertTrue(NetworkThreadConfig.EVENTS.stream().noneMatch(event -> event.startsWith("encode:")));
        NetworkThreadConfig.EVENTS.clear();

        bind.accept(Runnable::run);

        assertTrue(ConfigSyncRegistry.get(config.model().syncId()).ready());
        assertFalse(ServerConfigSync.beginHandshake(true).isEmpty());
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
        TestSyncPlatform.setClientMainThreadExecutor(Runnable::run);

        assertTrue(ServerConfigSync.beginHandshake(true).isEmpty());
        assertTrue(NetworkThreadConfig.EVENTS.isEmpty());
    }

    @Test
    void testClosingDuringActivationDoesNotResurrectRegistration(@TempDir Path tempDir) throws Exception {
        ConfigRegistryIsolation.beforeGameStartup();
        RegisteredConfig<NetworkThreadConfig> config = createNetworkThreadConfig(tempDir);

        NetworkThreadConfig.BEFORE_ENCODE = () -> ConfigRegistry.release(config);
        try {
            TestSyncPlatform.setClientMainThreadExecutor(Runnable::run);

            assertNull(ConfigSyncRegistry.get(config.model().syncId()));
            assertTrue(ServerConfigSync.beginHandshake(true).isEmpty());
        } finally {
            NetworkThreadConfig.BEFORE_ENCODE = () -> {
            };
        }
    }

    @Test
    void testIncomingSyncWorkRunsSynchronouslyOnCallerWithoutSaving(@TempDir Path tempDir) throws Exception {
        RegisteredConfig<NetworkThreadConfig> config = createNetworkThreadConfig(tempDir);
        ConfigBytes payload = encodeWith(config, candidate -> candidate.value = new ThreadValue(7));
        NetworkThreadConfig.EVENTS.clear();

        ClientSyncResult result = ClientConfigSync.receivePayload(
            new ConfigSyncS2CPacket(0, true, true, Map.of(config.model().syncId(), payload)));

        assertNull(result.disconnectReason());
        assertEquals(Set.of("decode", "validate", "encode", "listener"),
            NetworkThreadConfig.EVENTS.stream().map(event -> event.split(":")[0])
                .collect(java.util.stream.Collectors.toSet()));
        assertTrue(NetworkThreadConfig.EVENTS.stream().allMatch(
            event -> event.endsWith(":" + Thread.currentThread().getName())));

        assertEquals(new ThreadValue(7), config.state().published().value);
        assertTrue(Files.readString(tempDir.resolve("mod").resolve("network-thread.json5")).contains("1"));
    }

    @Test
    void testBroadcastEncodingRunsOnlyAfterTheServerAcceptsRecipients(@TempDir Path tempDir) throws Exception {
        RegisteredConfig<NetworkThreadConfig> config = createNetworkThreadConfig(tempDir);
        NetworkThreadConfig.EVENTS.clear();

        TestSyncPlatform.setBroadcastScheduler(packets -> {
            NetworkThreadConfig.record("send");
        });

        Deque<Runnable> actions = new ArrayDeque<>();
        TestSyncPlatform.setServerMainThreadExecutor(actions::addLast);
        drain(actions);

        NetworkThreadConfig changed = config.state().copyOfCanonical();
        changed.value = new ThreadValue(9);
        config.state().replace(changed);
        config.syncNotifier().notifyLocalChange();
        assertTrue(NetworkThreadConfig.EVENTS.isEmpty());
        assertEquals(1, actions.size());
        drain(actions);

        String thread = Thread.currentThread().getName();
        assertEquals(List.of("encode:" + thread, "send:" + thread), NetworkThreadConfig.EVENTS);
    }

    @Test
    void testRestartPayloadCompletesWithoutSchedulingADisconnect(@TempDir Path tempDir) throws Exception {
        RegisteredConfig<RestartSyncedConfig> config = create(RestartSyncedConfig.class, tempDir);
        ConfigBytes payload = encodeWith(config, candidate -> candidate.poolSize = 32);

        List<String> threads = new ArrayList<>();
        List<TranslationKey> disconnects = new ArrayList<>();
        TestSyncPlatform.setDisconnectScheduler(reason -> {
            threads.add(Thread.currentThread().getName());
            disconnects.add(reason);
        });

        ClientSyncResult result = ClientConfigSync.receivePayload(new ConfigSyncS2CPacket(
            0, true, true, Map.of(config.model().syncId(), payload)));

        assertTrue(result.restartRequired());
        assertNull(result.disconnectReason());
        assertTrue(disconnects.isEmpty());
        assertTrue(threads.isEmpty());

        assertEquals(8, config.state().published().poolSize);
        assertEquals(32, config.state().pending().orElseThrow().poolSize);
        assertTrue(Files.readString(tempDir.resolve("mod").resolve("restart-sync.json5")).contains("\"poolSize\": 32"));
    }

    @Test
    void testHandshakeRefreshDoesNotSuppressSynchronousBroadcast(@TempDir Path tempDir) {
        RegisteredConfig<OtherSyncedConfig> config = create(OtherSyncedConfig.class, tempDir);
        ConfigSyncHandshakeS2CPacket before = handshake();

        OtherSyncedConfig changed = config.state().copyOfCanonical();
        changed.enabled = false;
        config.state().replace(changed);

        Deque<Runnable> actions = new ArrayDeque<>();
        List<ConfigSyncS2CPacket> broadcasts = new ArrayList<>();
        TestSyncPlatform.setBroadcastScheduler(broadcasts::addAll);
        TestSyncPlatform.setServerMainThreadExecutor(actions::addLast);
        config.syncNotifier().notifyLocalChange();

        assertFalse(before.hashes().equals(handshake().hashes()));

        while (!actions.isEmpty()) {
            actions.removeFirst().run();
        }

        assertEquals(1, broadcasts.size());
    }

    @Test
    void testDeferredBroadcastDoesNotRunAfterServerStops(@TempDir Path tempDir) {
        RegisteredConfig<OtherSyncedConfig> config = create(OtherSyncedConfig.class, tempDir);
        Deque<Runnable> actions = new ArrayDeque<>();
        Executor server = actions::addLast;
        List<ConfigSyncS2CPacket> broadcasts = new ArrayList<>();
        AtomicInteger manifests = new AtomicInteger();
        TestSyncPlatform.setBroadcastScheduler(broadcasts::addAll);
        TestSyncPlatform.setManifestScheduler(manifests::incrementAndGet);
        TestSyncPlatform.setServerMainThreadExecutor(server);
        drain(actions);
        manifests.set(0);

        OtherSyncedConfig changed = config.state().copyOfCanonical();
        changed.enabled = false;
        config.state().replace(changed);
        config.syncNotifier().notifyLocalChange();
        ServerConfigSync.refreshManifest();
        assertTrue(broadcasts.isEmpty());
        assertEquals(1, actions.size());
        assertEquals(1, manifests.get());
        TestSyncPlatform.clearServerMainThreadExecutor(server);
        drain(actions);

        ServerConfigSync.refreshManifest();
        config.syncNotifier().notifyLocalChange();
        assertTrue(broadcasts.isEmpty());
        assertEquals(1, manifests.get());
    }

    @Test
    void testOldServerCleanupPreservesNewBinding() {
        Executor previous = Runnable::run;
        Executor current = action -> action.run();
        TestSyncPlatform.setServerMainThreadExecutor(previous);
        TestSyncPlatform.setServerMainThreadExecutor(current);

        TestSyncPlatform.clearServerMainThreadExecutor(previous);

        assertTrue(TestSyncPlatform.serverExecutor() == current);
    }

    @Test
    void testRequestSchedulerRunsSynchronouslyBeforeRegistrationIsReleased(@TempDir Path tempDir) {
        RegisteredConfig<OtherSyncedConfig> config = create(OtherSyncedConfig.class, tempDir);
        ClientConfigSync.receiveHandshake(new ConfigSyncHandshakeS2CPacket(
            true,
            Map.of(config.model().syncId(), ConfigBytes.of(new byte[ConfigSyncProtocol.HASH_BYTES]))));
        Deque<Runnable> actions = new ArrayDeque<>();
        List<ConfigSyncRequestC2SPacket> requests = new ArrayList<>();
        TestSyncPlatform.setClientMainThreadExecutor(actions::addLast);
        TestSyncPlatform.setRequestScheduler(requests::add);
        ClientConfigSync.requestIfAdvertised(ConfigSyncRegistry.get(config.model().syncId()));
        assertEquals(1, requests.size());

        ConfigRegistry.release(config);
        drain(actions);

        assertEquals(1, requests.size());
        assertEquals(Set.of(config.model().syncId()), requests.getFirst().configIds());
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
        return ServerConfigSync.beginHandshake(true).getFirst();
    }

    private static boolean apply(String id, ConfigBytes payload) {
        return receive(Map.of(id, payload));
    }

    private static boolean receive(Map<String, ConfigBytes> configs) {
        ClientSyncResult result = ClientConfigSync.receivePayload(
            new ConfigSyncS2CPacket(0, true, true, configs));
        assertTrue(result.completed());
        assertNull(result.disconnectReason());
        return result.restartRequired();
    }

    private static <T> ConfigBytes encodeWith(RegisteredConfig<T> config, Consumer<T> mutator) {
        var original = config.state().checkpoint();
        T candidate = config.state().copyOfCanonical();
        mutator.accept(candidate);
        config.state().replace(candidate);
        try {
            return SyncedConfig.of(config).cachedSnapshot().data();
        } finally {
            config.state().restore(original);
        }
    }

    private static RestartSyncStore restartStore(Path directory, String id) {
        return new RestartSyncStore(directory, new ConfigScope("mod"), id,
            ClientConfigSync.connectionIdentity());
    }

    private static void blockCacheDirectory(Path directory) {
        try {
            Files.createFile(directory.resolve("liteconfig"));
        } catch (java.io.IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
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

    @Config(name = "other-sync", sync = SyncMode.ENABLED)
    public static class OtherSyncedConfig {
        public boolean enabled = true;
    }

    public record ThreadValue(int value) {
    }

    @Config(name = "network-thread", sync = SyncMode.ENABLED)
    public static class NetworkThreadConfig implements ConfigExtension {
        private static final List<String> EVENTS = new ArrayList<>();
        private static Runnable BEFORE_ENCODE = () -> {
        };

        @Entry(listener = "changed")
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
            record("listener");
        }
    }

    @Config(name = "reset-during-save", sync = SyncMode.ENABLED)
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
        @Entry(sync = SyncMode.ENABLED)
        public int shared = 1;
        public String local = "local";
    }

    @Config(name = "restart-sync", sync = SyncMode.ENABLED)
    public static class RestartSyncedConfig {
        @Entry(restart = true)
        public int poolSize = 8;
        public String greeting = "hello";
    }

    @Config(name = "other-restart-sync", sync = SyncMode.ENABLED)
    public static class OtherRestartSyncedConfig {
        @Entry(restart = true)
        public int poolSize = 8;
    }

    @Config(name = "strict-sync", sync = SyncMode.ENABLED,
        readFailurePolicy = FailurePolicy.STRICT,
        writeFailurePolicy = FailurePolicy.STRICT,
        updateFailurePolicy = FailurePolicy.STRICT)
    public static class StrictSyncedConfig {
        public int value = 1;
    }

    @Config(name = "client-sync", type = ConfigType.CLIENT, sync = SyncMode.ENABLED)
    public static class ClientSyncedConfig {
        public int value = 1;
    }

    @Config(name = "world-sync", type = ConfigType.WORLD, sync = SyncMode.ENABLED)
    public static class WorldSyncedConfig {
        public int value = 1;
    }

    @Config(name = "unsupported-sync", sync = SyncMode.ENABLED)
    public static class UnsupportedSyncedConfig {
        public java.util.LinkedList<String> value = new java.util.LinkedList<>(List.of("one"));
    }
}
