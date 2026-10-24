package com.gmalvestiti.minecraft.liteconfig.network;

import com.google.common.hash.Hashing;
import com.gmalvestiti.minecraft.liteconfig.api.ConfigHolder;
import com.gmalvestiti.minecraft.liteconfig.api.LiteConfig;
import com.gmalvestiti.minecraft.liteconfig.api.SyncMode;
import com.gmalvestiti.minecraft.liteconfig.api.ConfigType;
import com.gmalvestiti.minecraft.liteconfig.api.RestartSyncPolicy;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Config;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Entry;
import com.gmalvestiti.minecraft.liteconfig.context.ConfigSettings;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigScope;
import com.gmalvestiti.minecraft.liteconfig.network.packet.ConfigSyncHandshakeS2CPacket;
import com.gmalvestiti.minecraft.liteconfig.network.packet.ConfigSyncRequestC2SPacket;
import com.gmalvestiti.minecraft.liteconfig.network.packet.ConfigSyncS2CPacket;
import com.gmalvestiti.minecraft.liteconfig.registry.ConfigRegistry;
import com.gmalvestiti.minecraft.liteconfig.registry.RegisteredConfig;
import com.gmalvestiti.minecraft.liteconfig.shared.TranslationKey;
import com.gmalvestiti.minecraft.liteconfig.support.ConfigRegistryIsolation;
import com.gmalvestiti.minecraft.liteconfig.support.TestSyncPlatform;
import com.mojang.serialization.Codec;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(ConfigRegistryIsolation.class)
class HashFirstSyncTest {
    private final List<ConfigSyncRequestC2SPacket> requests = new ArrayList<>();

    @BeforeEach
    void initialize() {
        TestSyncPlatform.setRequestScheduler(requests::add);
        TestSyncPlatform.setRemoteConnectionCheck(() -> true);
    }

    @Test
    void equalLocalHashLocksTheHolderWithoutRequestingPayloadOrWritingPreferences(@TempDir Path directory)
        throws Exception {
        RegisteredConfig<Rules> config = create(Rules.class, directory);
        ConfigHolder<Rules> holder = config.access();
        Path file = directory.resolve("mod").resolve("hash-rules.json5");
        String before = Files.readString(file);
        AtomicInteger events = new AtomicInteger();
        holder.onUpdate(value -> events.incrementAndGet());

        advertise(config.model().syncId(), SyncedConfig.of(config).cachedSnapshot().data());

        assertTrue(requests.isEmpty());
        assertTrue(config.state().remoteActive());
        assertFalse(holder.update(value -> value.shared = 99).accepted());
        assertEquals(before, Files.readString(file));
        assertEquals(0, events.get());
        ClientConfigSync.resetClientConnection();
        assertFalse(config.state().remoteActive());
        assertTrue(holder.update(value -> value.shared = 2).accepted());
    }

    @Test
    void unchangedRemoteSnapshotIsReplayedFromServerCacheAfterDisconnect(@TempDir Path directory)
        throws Exception {
        RegisteredConfig<Rules> config = create(Rules.class, directory);
        ConfigBytes remote = encode(config, value -> value.shared = 9);
        Path file = directory.resolve("mod").resolve("hash-rules.json5");
        String localFile = Files.readString(file);
        ClientSyncResult first = ClientConfigSync.receivePayload(
            new ConfigSyncS2CPacket(0, true, true, Map.of(config.model().syncId(), remote)));
        assertNull(first.disconnectReason());
        assertEquals(9, config.state().published().shared);
        ClientConfigSync.resetClientConnection();
        assertEquals(1, config.state().published().shared);

        advertise(config.model().syncId(), remote);

        assertTrue(requests.isEmpty());
        assertTrue(config.state().remoteActive());
        assertEquals(9, config.state().published().shared);
        assertEquals("local", config.state().published().local);
        assertEquals(localFile, Files.readString(file));
    }

    @Test
    void persistedSnapshotCanBeReusedAfterHolderRecreationWithoutARequest(@TempDir Path directory) {
        RegisteredConfig<Rules> first = create(Rules.class, directory);
        ConfigBytes remote = encode(first, value -> value.shared = 9);
        assertNull(ClientConfigSync.receivePayload(
            new ConfigSyncS2CPacket(0, true, true, Map.of(first.model().syncId(), remote))).disconnectReason());
        ClientConfigSync.resetClientConnection();
        ConfigRegistry.release(first);

        RegisteredConfig<Rules> recreated = create(Rules.class, directory);
        assertEquals(1, recreated.state().published().shared);
        advertise(recreated.model().syncId(), remote);

        assertTrue(requests.isEmpty());
        assertEquals(9, recreated.state().published().shared);
        assertTrue(recreated.state().remoteActive());
    }

    @Test
    void anotherServerCannotReuseThePreviousServersSnapshot(@TempDir Path directory) {
        RegisteredConfig<Rules> config = create(Rules.class, directory);
        ConfigBytes remote = encode(config, value -> value.shared = 9);
        assertNull(ClientConfigSync.receivePayload(
            new ConfigSyncS2CPacket(0, true, true, Map.of(config.model().syncId(), remote))).disconnectReason());
        ClientConfigSync.resetClientConnection();
        TestSyncPlatform.setConnectionIdentitySupplier(() -> "different-server");

        advertise(config.model().syncId(), remote);

        assertEquals(1, requests.size());
        assertEquals(Set.of(config.model().syncId()), requests.getFirst().configIds());
        assertFalse(config.state().remoteActive());
    }

    @Test
    void aChangedServerHashRequestsPayloadInsteadOfApplyingStaleCache(@TempDir Path directory) {
        RegisteredConfig<Rules> config = create(Rules.class, directory);
        ConfigBytes first = encode(config, value -> value.shared = 9);
        assertNull(ClientConfigSync.receivePayload(
            new ConfigSyncS2CPacket(0, true, true, Map.of(config.model().syncId(), first))).disconnectReason());
        ClientConfigSync.resetClientConnection();
        ConfigBytes changed = encode(config, value -> value.shared = 10);

        advertise(config.model().syncId(), changed);

        assertEquals(1, requests.size());
        assertEquals(Set.of(config.model().syncId()), requests.getFirst().configIds());
        assertEquals(1, config.state().published().shared);
        assertFalse(config.state().remoteActive());
    }

    @Test
    void lateRegistrationUsesTheRememberedHashWithoutRequestingMatchingValues(@TempDir Path directory) {
        RegisteredConfig<Rules> temporary = create(Rules.class, directory);
        String id = temporary.model().syncId();
        ConfigBytes payload = SyncedConfig.of(temporary).cachedSnapshot().data();
        ConfigRegistry.release(temporary);
        advertise(id, payload);
        assertTrue(requests.isEmpty());

        RegisteredConfig<Rules> registered = create(Rules.class, directory);

        assertTrue(requests.isEmpty());
        assertTrue(registered.state().remoteActive());
    }

    @Test
    void repeatedServerLoginsReuseEncodedSnapshotsAndHashes(@TempDir Path directory) {
        LiteConfig.codecs()
            .registerCodec(Counted.class, Counted.CODEC)
            .registerStreamCodec(Counted.class, Counted.STREAM_CODEC);
        create(CountedRules.class, directory);
        int encodes = Counted.ENCODES.get();
        List<ConfigSyncHandshakeS2CPacket> first = ServerConfigSync.beginHandshake(true);
        assertFalse(first.isEmpty());

        for (int login = 0; login < 20; login++) {
            assertEquals(first, ServerConfigSync.beginHandshake(true));
        }

        assertEquals(encodes, Counted.ENCODES.get(), "Login must not re-encode unchanged server state");
    }

    private static <T> RegisteredConfig<T> create(Class<T> type, Path directory) {
        return ConfigRegistry.register(new ConfigSettings<>(type, new ConfigScope("mod"), directory));
    }

    @Test
    void worldRestartValuesActivateOnJoinAndReplayWithoutCrossServerLeakage(@TempDir Path directory) {
        RegisteredConfig<WorldRules> config = create(WorldRules.class, directory);
        ConfigBytes first = encode(config, value -> value.restart = 8);
        String id = config.model().syncId();
        ClientSyncResult initial = ClientConfigSync.receivePayload(new ConfigSyncS2CPacket(0, true, true, Map.of(id, first)));
        assertNull(initial.disconnectReason());
        assertFalse(initial.restartRequired());
        assertEquals(8, config.state().published().restart);
        ConfigBytes updated = encode(config, value -> value.restart = 9);
        assertTrue(ClientConfigSync.receivePayload(
            new ConfigSyncS2CPacket(0, true, false, Map.of(id, updated))).restartRequired());
        assertEquals(8, config.state().published().restart);
        assertEquals(9, config.state().pending().orElseThrow().restart);
        ClientConfigSync.resetClientConnection();
        assertEquals(5, config.state().published().restart);
        assertTrue(config.state().pending().isEmpty());
        advertise(id, updated);
        assertTrue(requests.isEmpty());
        assertEquals(9, config.state().published().restart);
        assertTrue(config.state().pending().isEmpty());
        ClientConfigSync.resetClientConnection();
        TestSyncPlatform.setConnectionIdentitySupplier(() -> "world-b");
        advertise(id, first);
        assertEquals(1, requests.size());
        assertEquals(5, config.state().published().restart);
        assertNull(ClientConfigSync.receivePayload(
            new ConfigSyncS2CPacket(0, true, true, Map.of(id, first))).disconnectReason());
        assertEquals(8, config.state().published().restart);
        assertTrue(config.state().pending().isEmpty());
        ClientConfigSync.resetClientConnection();
        TestSyncPlatform.setConnectionIdentitySupplier(() -> "test-server");
        advertise(id, updated);
        assertEquals(9, config.state().published().restart);
    }

    @Config(name = "world-rules", type = ConfigType.WORLD, sync = SyncMode.ENABLED)
    static class WorldRules {
        @Entry(restart = true)
        int restart = 5;
    }

    @Test
    void strictWorldChangesStayPendingAndActivateOnTheNextJoin(@TempDir Path directory) {
        RegisteredConfig<StrictWorldRules> config = create(StrictWorldRules.class, directory);
        String id = config.model().syncId();
        ConfigBytes initial = encode(config, value -> value.restart = 8);
        assertNull(ClientConfigSync.receivePayload(
            new ConfigSyncS2CPacket(0, true, true, Map.of(id, initial))).disconnectReason());
        ConfigBytes changed = encode(config, value -> value.restart = 9);
        ClientSyncResult result = ClientConfigSync.receivePayload(
            new ConfigSyncS2CPacket(0, true, false, Map.of(id, changed)));
        assertTrue(result.restartRequired());
        assertNull(result.disconnectReason());
        assertEquals(8, config.state().published().restart);
        assertEquals(9, config.state().pending().orElseThrow().restart);
        ClientConfigSync.resetClientConnection();
        advertise(id, changed);
        assertEquals(9, config.state().published().restart);
        assertTrue(config.state().pending().isEmpty());
    }

    @Config(name = "strict-world-rules", type = ConfigType.WORLD, sync = SyncMode.ENABLED,
        restartSyncPolicy = RestartSyncPolicy.DISCONNECT)
    static class StrictWorldRules {
        @Entry(restart = true)
        int restart = 5;
    }

    @Test
    void serverJoinMismatchDisconnectsRegardlessOfConfigOrder(@TempDir Path directory) {
        RegisteredConfig<StrictWorldRules> world = create(StrictWorldRules.class, directory);
        RegisteredConfig<StrictServerRules> server = create(StrictServerRules.class, directory);
        String worldId = world.model().syncId();
        String serverId = server.model().syncId();
        ConfigBytes changedWorld = encode(world, value -> value.restart = 9);
        ConfigBytes changedServer = encode(server, value -> value.restart = 10);
        for (boolean worldFirst : List.of(true, false)) {
            Map<String, ConfigBytes> payload = new java.util.LinkedHashMap<>();
            payload.put(worldFirst ? worldId : serverId, worldFirst ? changedWorld : changedServer);
            payload.put(worldFirst ? serverId : worldId, worldFirst ? changedServer : changedWorld);
            assertEquals(TranslationKey.RESTART_REQUIRED_SERVER,
                ClientConfigSync.receivePayload(new ConfigSyncS2CPacket(0, true, true, payload)).disconnectReason());
            assertEquals(9, world.access().data().restart);
            assertTrue(world.access().pending().isEmpty());
            ClientConfigSync.resetClientConnection();
        }
    }

    @Config(name = "strict-server-rules", sync = SyncMode.ENABLED,
        restartSyncPolicy = RestartSyncPolicy.DISCONNECT)
    static class StrictServerRules {
        @Entry(restart = true)
        int restart = 5;
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

    private static void advertise(String id, ConfigBytes payload) {
        ConfigBytes hash = ConfigBytes.of(Hashing.sha256().hashBytes(payload.bytes()).asBytes());
        ClientConfigSync.receiveHandshake(new ConfigSyncHandshakeS2CPacket(true, Map.of(id, hash)));
    }

    @Config(name = "hash-rules", sync = SyncMode.ENABLED)
    public static class Rules {
        public int shared = 1;
        @Entry(sync = SyncMode.DISABLED)
        public String local = "local";
    }

    @Config(name = "counted-hash-rules", sync = SyncMode.ENABLED)
    public static class CountedRules {
        public Counted value = new Counted(1);
    }

    public record Counted(int value) {
        static final AtomicInteger ENCODES = new AtomicInteger();
        static final Codec<Counted> CODEC = Codec.INT.xmap(Counted::new, Counted::value);
        static final StreamCodec<ByteBuf, Counted> STREAM_CODEC = new StreamCodec<>() {
            @Override
            public Counted decode(ByteBuf buffer) {
                return new Counted(buffer.readInt());
            }

            @Override
            public void encode(ByteBuf buffer, Counted value) {
                ENCODES.incrementAndGet();
                buffer.writeInt(value.value());
            }
        };
    }
}
