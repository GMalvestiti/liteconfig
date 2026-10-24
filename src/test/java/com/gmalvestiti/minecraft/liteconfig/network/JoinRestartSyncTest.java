package com.gmalvestiti.minecraft.liteconfig.network;

import com.google.common.hash.Hashing;
import com.gmalvestiti.minecraft.liteconfig.api.ConfigType;
import com.gmalvestiti.minecraft.liteconfig.api.RestartSyncPolicy;
import com.gmalvestiti.minecraft.liteconfig.api.SyncMode;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Config;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Entry;
import com.gmalvestiti.minecraft.liteconfig.context.ConfigSettings;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigScope;
import com.gmalvestiti.minecraft.liteconfig.network.packet.ConfigSyncHandshakeS2CPacket;
import com.gmalvestiti.minecraft.liteconfig.network.packet.ConfigSyncRequestC2SPacket;
import com.gmalvestiti.minecraft.liteconfig.network.packet.ConfigSyncS2CPacket;
import com.gmalvestiti.minecraft.liteconfig.platform.CommonServerPlatform;
import com.gmalvestiti.minecraft.liteconfig.registry.ConfigRegistry;
import com.gmalvestiti.minecraft.liteconfig.registry.RegisteredConfig;
import com.gmalvestiti.minecraft.liteconfig.shared.TranslationKey;
import com.gmalvestiti.minecraft.liteconfig.support.ConfigRegistryIsolation;
import com.gmalvestiti.minecraft.liteconfig.support.TestSyncPlatform;
import net.minecraft.SharedConstants;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(ConfigRegistryIsolation.class)
class JoinRestartSyncTest {

    private final List<TranslationKey> disconnects = new ArrayList<>();
    private final List<ConfigSyncRequestC2SPacket> requests = new ArrayList<>();

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @BeforeEach
    void initialize() {
        TestSyncPlatform.setRemoteConnectionCheck(() -> true);
        TestSyncPlatform.setDisconnectScheduler(disconnects::add);
        TestSyncPlatform.setRequestScheduler(requests::add);
    }

    @Test
    void joinMismatchIsCommittedBeforeDisconnecting(@TempDir Path directory) throws Exception {
        RegisteredConfig<JoinRules> config = create(JoinRules.class, directory);
        ConfigBytes remote = encode(config, value -> {
            value.restart = 8;
            value.immediate = 9;
        });
        TestSyncPlatform.setDisconnectScheduler(reason -> {
            assertEquals(5, config.access().data().restart);
            assertEquals(8, config.access().pending().orElseThrow().restart);
            assertEquals(9, config.access().data().immediate);
            disconnects.add(reason);
        });

        ClientSyncResult result = receive(config, remote, true);

        assertTrue(result.completed());
        assertTrue(result.restartRequired());
        assertEquals(List.of(TranslationKey.RESTART_REQUIRED_SERVER), disconnects);
        assertTrue(Files.readString(config.access().path().orElseThrow()).contains("\"restart\": 8"));
    }

    @Test
    void liveTransactionDefersUntilReconnectThenCachedJoinRequestsRestart(@TempDir Path directory)
        throws Exception {
        RegisteredConfig<JoinRules> config = create(JoinRules.class, directory);
        assertNull(receive(config, encode(config, value -> {}), true).disconnectReason());
        ConfigBytes remote = encode(config, value -> {
            value.restart = 8;
            value.immediate = 9;
        });
        ClientSyncResult pending = ClientConfigSync.receivePayload(new ConfigSyncS2CPacket(
            10, false, false, Map.of(config.model().syncId(), remote)));
        assertFalse(pending.completed());
        assertTrue(config.access().pending().isEmpty());

        ClientSyncResult completed = ClientConfigSync.receivePayload(
            new ConfigSyncS2CPacket(10, true, false, Map.of()));

        assertTrue(completed.restartRequired());
        assertNull(completed.disconnectReason());
        assertTrue(disconnects.isEmpty());
        assertEquals(5, config.access().data().restart);
        assertEquals(9, config.access().data().immediate);
        assertEquals(8, config.access().pending().orElseThrow().restart);
        assertTrue(Files.readString(config.access().path().orElseThrow()).contains("\"restart\": 8"));

        ClientConfigSync.resetClientConnection();
        advertise(config.model().syncId(), remote, true);
        assertTrue(requests.isEmpty());
        assertEquals(List.of(TranslationKey.RESTART_REQUIRED_SERVER), disconnects);
        assertEquals(5, config.access().data().restart);

        ClientConfigSync.resetClientConnection();
        ConfigRegistry.release(config);
        RegisteredConfig<JoinRules> restarted = create(JoinRules.class, directory);
        assertEquals(8, restarted.access().data().restart);
        disconnects.clear();
        advertise(restarted.model().syncId(), remote, true);
        assertTrue(disconnects.isEmpty());
        assertTrue(restarted.access().pending().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void cachedManifestHonorsItsJoinFlagEvenOnFirstSync(boolean joining, @TempDir Path directory) {
        RegisteredConfig<JoinRules> config = create(JoinRules.class, directory);
        ConfigBytes remote = encode(config, value -> value.restart = 8);
        new RestartSyncStore(directory, config.model().scope(), config.model().syncId(),
            ClientConfigSync.connectionIdentity()).write(remote);

        advertise(config.model().syncId(), remote, joining);

        assertTrue(requests.isEmpty());
        assertEquals(joining ? List.of(TranslationKey.RESTART_REQUIRED_SERVER) : List.of(), disconnects);
        assertEquals(5, config.access().data().restart);
        assertEquals(8, config.access().pending().orElseThrow().restart);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void payloadRequestsAndResponsesPreserveManifestJoinFlag(boolean joining, @TempDir Path directory) {
        RegisteredConfig<JoinRules> config = create(JoinRules.class, directory);
        ConfigBytes remote = encode(config, value -> value.restart = 8);
        advertise(config.model().syncId(), remote, joining);
        assertEquals(1, requests.size());
        assertEquals(joining, requests.getFirst().joining());

        var before = config.state().checkpoint();
        JoinRules server = config.state().copyOfCanonical();
        server.restart = 8;
        config.state().replace(server);
        List<ConfigSyncS2CPacket> packets;
        try {
            packets = ServerConfigSync.payloadsFor(requests.getFirst());
        } finally {
            config.state().restore(before);
        }
        assertFalse(packets.isEmpty());
        assertTrue(packets.stream().allMatch(packet -> packet.joining() == joining));
        packets.forEach(ClientConfigSync::receivePayload);
        assertEquals(joining ? List.of(TranslationKey.RESTART_REQUIRED_SERVER) : List.of(), disconnects);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void lateRegistrationRetainsTheRememberedManifestJoinFlag(boolean joining, @TempDir Path directory) {
        RegisteredConfig<JoinRules> original = create(JoinRules.class, directory);
        String id = original.model().syncId();
        ConfigBytes remote = encode(original, value -> value.restart = 8);
        ConfigRegistry.release(original);
        advertise(id, remote, joining);
        assertTrue(requests.isEmpty());

        RegisteredConfig<JoinRules> registered = create(JoinRules.class, directory);

        assertEquals(1, requests.size());
        assertEquals(joining, requests.getFirst().joining());
        assertEquals(Set.of(id), requests.getFirst().configIds());
        receive(registered, remote, requests.getFirst().joining());
        assertEquals(joining ? List.of(TranslationKey.RESTART_REQUIRED_SERVER) : List.of(), disconnects);
    }

    @Test
    void worldJoinAppliesRestartValuesAndLiveChangesWaitWithoutDisconnecting(@TempDir Path directory) {
        RegisteredConfig<JoinWorldRules> config = create(JoinWorldRules.class, directory);
        assertFalse(receive(config, encode(config, value -> value.restart = 8), true).restartRequired());
        assertEquals(8, config.access().data().restart);
        ConfigBytes changed = encode(config, value -> value.restart = 9);

        ClientSyncResult live = receive(config, changed, false);

        assertTrue(live.restartRequired());
        assertNull(live.disconnectReason());
        assertTrue(disconnects.isEmpty());
        assertEquals(8, config.access().data().restart);
        assertEquals(9, config.access().pending().orElseThrow().restart);
        ClientConfigSync.resetClientConnection();
        advertise(config.model().syncId(), changed, true);
        assertEquals(9, config.access().data().restart);
        assertTrue(config.access().pending().isEmpty());
        assertTrue(disconnects.isEmpty());
    }

    @Test
    void disconnectPolicyDefersLiveChanges(@TempDir Path directory) {
        RegisteredConfig<JoinRules> config = create(JoinRules.class, directory);
        ClientSyncResult result = receive(config, encode(config, value -> value.restart = 8), false);
        assertTrue(result.restartRequired());
        assertNull(result.disconnectReason());
        assertTrue(disconnects.isEmpty());
        assertEquals(5, config.access().data().restart);
        assertEquals(8, config.access().pending().orElseThrow().restart);
    }

    @Test
    void serverBroadcastsAreLiveAndPendingOnlyEditsDoNotBroadcast(@TempDir Path directory) {
        TestSyncPlatform.setRemoteConnectionCheck(() -> false);
        RegisteredConfig<JoinRules> config = create(JoinRules.class, directory);
        List<ConfigSyncS2CPacket> broadcasts = new ArrayList<>();
        TestSyncPlatform.setBroadcastScheduler(broadcasts::addAll);
        assertTrue(ServerConfigSync.beginHandshake(true).stream().allMatch(ConfigSyncHandshakeS2CPacket::joining));
        assertTrue(ServerConfigSync.beginHandshake(false).stream().noneMatch(ConfigSyncHandshakeS2CPacket::joining));

        assertTrue(config.access().update(value -> value.immediate = 9).accepted());
        assertEquals(1, broadcasts.size());
        assertFalse(broadcasts.getFirst().joining());
        assertTrue(config.access().update(value -> value.restart = 8).accepted());
        assertEquals(1, broadcasts.size());
        assertEquals(5, config.access().data().restart);
        assertEquals(8, config.access().pending().orElseThrow().restart);
    }

    @Test
    void refreshedManifestsAreLiveForAlreadyConnectedPlayers(@TempDir Path directory) {
        TestSyncPlatform.setRemoteConnectionCheck(() -> false);
        create(JoinRules.class, directory);
        MinecraftServer server = mock(MinecraftServer.class);
        PlayerList players = mock(PlayerList.class);
        ServerPlayer player = mock(ServerPlayer.class);
        when(server.getPlayerList()).thenReturn(players);
        when(players.getPlayers()).thenReturn(List.of(player));
        doAnswer(call -> {
            call.<Runnable>getArgument(0).run();
            return null;
        }).when(server).execute(any(Runnable.class));
        List<CustomPacketPayload> sent = new ArrayList<>();
        CommonServerPlatform platform = new CommonServerPlatform(server) {
            @Override
            protected boolean canSend(ServerPlayer recipient, CustomPacketPayload.Type<?> type) {
                return true;
            }

            @Override
            protected void send(ServerPlayer recipient, CustomPacketPayload packet) {
                sent.add(packet);
            }
        };
        CommonServerPlatform.bind(platform);
        sent.clear();

        platform.refreshManifest();

        assertFalse(sent.isEmpty());
        for (CustomPacketPayload packet : sent) {
            assertFalse(assertInstanceOf(ConfigSyncHandshakeS2CPacket.class, packet).joining());
        }
    }

    @Test
    void malformedLivePayloadStillDisconnectsWithSyncFailure(@TempDir Path directory) {
        RegisteredConfig<JoinRules> config = create(JoinRules.class, directory);
        ClientSyncResult result = receive(config, ConfigBytes.of(new byte[0]), false);
        assertEquals(TranslationKey.SYNC_FAILED, result.disconnectReason());
        assertEquals(List.of(TranslationKey.SYNC_FAILED), disconnects);
        assertFalse(config.state().remoteActive());
        assertTrue(config.access().pending().isEmpty());
    }

    private static <T> RegisteredConfig<T> create(Class<T> type, Path directory) {
        return ConfigRegistry.register(new ConfigSettings<>(type, new ConfigScope("mod"), directory));
    }

    private static <T> ConfigBytes encode(RegisteredConfig<T> config, Consumer<T> change) {
        var before = config.state().checkpoint();
        T candidate = config.state().copyOfCanonical();
        change.accept(candidate);
        config.state().replace(candidate);
        try {
            return SyncedConfig.of(config).cachedSnapshot().data();
        } finally {
            config.state().restore(before);
        }
    }

    private static ClientSyncResult receive(RegisteredConfig<?> config, ConfigBytes payload, boolean joining) {
        return ClientConfigSync.receivePayload(
            new ConfigSyncS2CPacket(0, true, joining, Map.of(config.model().syncId(), payload)));
    }

    private static void advertise(String id, ConfigBytes payload, boolean joining) {
        ConfigBytes hash = ConfigBytes.of(Hashing.sha256().hashBytes(payload.bytes()).asBytes());
        ClientConfigSync.receiveHandshake(new ConfigSyncHandshakeS2CPacket(joining, Map.of(id, hash)));
    }

    @Config(name = "join-rules", sync = SyncMode.ENABLED,
        restartSyncPolicy = RestartSyncPolicy.DISCONNECT)
    static class JoinRules {
        @Entry(restart = true)
        int restart = 5;
        int immediate = 1;
    }

    @Config(name = "join-world-rules", type = ConfigType.WORLD, sync = SyncMode.ENABLED,
        restartSyncPolicy = RestartSyncPolicy.DISCONNECT)
    static class JoinWorldRules {
        @Entry(restart = true)
        int restart = 5;
    }

}
