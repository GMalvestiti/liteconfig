package com.gmalvestiti.minecraft.liteconfig.support;

import com.gmalvestiti.minecraft.liteconfig.network.ClientConfigSync;
import com.gmalvestiti.minecraft.liteconfig.network.packet.ConfigSyncHandshakeS2CPacket;
import com.gmalvestiti.minecraft.liteconfig.network.packet.ConfigSyncRequestC2SPacket;
import com.gmalvestiti.minecraft.liteconfig.network.packet.ConfigSyncS2CPacket;
import com.gmalvestiti.minecraft.liteconfig.platform.CommonClientPlatform;
import com.gmalvestiti.minecraft.liteconfig.platform.CommonServerPlatform;
import com.gmalvestiti.minecraft.liteconfig.platform.ConnectionType;
import com.gmalvestiti.minecraft.liteconfig.shared.TranslationKey;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.mockito.Mockito.mock;

/** Mutable fake runtime for tests that exercise one service at a time. */
public final class TestSyncPlatform {
    static {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static FakeClient client = new FakeClient(Runnable::run);
    private static FakeServer server = new FakeServer(Runnable::run);

    private TestSyncPlatform() {}

    public static UUID serverId() {
        return idFor(client.identity.get());
    }

    private static UUID idFor(String identity) {
        return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
    }

    public static void reset() {
        client = new FakeClient(Runnable::run);
        server = new FakeServer(Runnable::run);
        ClientConfigSync.bind(client);
        CommonServerPlatform.bind(server);
    }

    public static void setClientMainThreadExecutor(Executor executor) {
        FakeClient replacement = new FakeClient(executor);
        replacement.remote = client.remote;
        replacement.identity = client.identity;
        replacement.requests = client.requests;
        replacement.disconnects = client.disconnects;
        client = replacement;
        ClientConfigSync.bind(client);
    }

    public static void setServerMainThreadExecutor(Executor executor) {
        FakeServer replacement = new FakeServer(executor);
        replacement.broadcasts = server.broadcasts;
        replacement.manifests = server.manifests;
        replacement.syncRecipientsAvailable = server.syncRecipientsAvailable;
        server = replacement;
        CommonServerPlatform.bind(server);
    }

    public static void clearServerMainThreadExecutor(Executor executor) {
        CommonServerPlatform.unbind(executor);
    }

    public static Executor serverExecutor() {
        return server.executor;
    }

    public static void setRemoteConnectionCheck(BooleanSupplier check) { client.remote = check; }
    public static void setConnectionIdentitySupplier(Supplier<String> identity) { client.identity = identity; }
    public static void setRequestScheduler(Consumer<ConfigSyncRequestC2SPacket> requests) { client.requests = requests; }
    public static void setDisconnectScheduler(Consumer<TranslationKey> disconnects) { client.disconnects = disconnects; }
    public static void setBroadcastScheduler(Consumer<List<ConfigSyncS2CPacket>> broadcasts) { server.broadcasts = broadcasts; }
    public static void setSyncRecipientsAvailable(boolean available) { server.syncRecipientsAvailable = available; }
    public static void setManifestScheduler(Runnable manifests) { server.manifests = manifests; }

    private static final class FakeClient extends CommonClientPlatform {
        private final Executor executor;
        private BooleanSupplier remote = () -> false;
        private Supplier<String> identity = () -> "test-server";
        private Consumer<ConfigSyncRequestC2SPacket> requests = ignored -> {};
        private Consumer<TranslationKey> disconnects = ignored -> {};

        private FakeClient(Executor executor) {
            super(mock(Minecraft.class));
            this.executor = executor;
        }
        @Override public void execute(Runnable action) { executor.execute(action); }
        @Override public ConnectionType connectionType() {
            return remote.getAsBoolean() ? ConnectionType.REMOTE : ConnectionType.DISCONNECTED;
        }
        @Override public String connectionIdentity() { return idFor(identity.get()).toString(); }
        @Override public Object connectionToken() { return this; }
        @Override public void request(ConfigSyncRequestC2SPacket packet) { requests.accept(packet); }
        @Override public void disconnect(TranslationKey reason) { disconnects.accept(reason); }
    }

    private static final class FakeServer extends CommonServerPlatform {
        private final Executor executor;
        private Consumer<List<ConfigSyncS2CPacket>> broadcasts = ignored -> {};
        private Runnable manifests = () -> {};
        private boolean syncRecipientsAvailable = true;

        private FakeServer(Executor executor) {
            super(mock(MinecraftServer.class));
            this.executor = executor;
        }
        @Override public Object identity() { return executor; }
        @Override public void execute(Runnable action) { executor.execute(action); }
        @Override public void broadcast(Supplier<List<ConfigSyncS2CPacket>> packets) {
            execute(() -> {
                if (CommonServerPlatform.current() == this && syncRecipientsAvailable) {
                    List<ConfigSyncS2CPacket> payloads = packets.get();
                    if (!payloads.isEmpty()) {
                        broadcasts.accept(payloads);
                    }
                }
            });
        }
        @Override public void announce(Supplier<List<ConfigSyncHandshakeS2CPacket>>         packets) {
                    manifests.run();
                }
        @Override protected boolean canSend(ServerPlayer player, CustomPacketPayload.Type<?> type) { return false; }
        @Override protected void send(ServerPlayer player, CustomPacketPayload packet) {}
    }
}
