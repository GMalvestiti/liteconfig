package com.gmalvestiti.minecraft.liteconfig.platform.neoforge;

//? if neoforge {
/*import com.gmalvestiti.minecraft.liteconfig.LiteConfigCommon;
import com.gmalvestiti.minecraft.liteconfig.engine.ConfigEventThreads;
import com.gmalvestiti.minecraft.liteconfig.network.ConfigSyncRegistry;
import com.gmalvestiti.minecraft.liteconfig.network.ClientConfigSync;
import com.gmalvestiti.minecraft.liteconfig.network.ServerConfigSync;
import com.gmalvestiti.minecraft.liteconfig.network.packet.ConfigSyncHandshakeS2CPacket;
import com.gmalvestiti.minecraft.liteconfig.network.packet.ConfigSyncRequestC2SPacket;
import com.gmalvestiti.minecraft.liteconfig.network.packet.ConfigSyncS2CPacket;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.HandlerThread;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

@Mod(LiteConfigCommon.MOD_ID)
public class NeoForgeEntrypoint {

    public NeoForgeEntrypoint(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(NeoForgeEntrypoint::onRegisterPayload);

        NeoForge.EVENT_BUS.addListener(NeoForgeEntrypoint::onServerStarted);
        NeoForge.EVENT_BUS.addListener(NeoForgeEntrypoint::onServerStopped);
        NeoForge.EVENT_BUS.addListener(NeoForgeEntrypoint::onPlayerLoggedIn);

        ServerConfigSync.setBroadcastScheduler(NeoForgeEntrypoint::scheduleBroadcast);
        ServerConfigSync.setManifestScheduler(NeoForgeEntrypoint::scheduleManifest);

        ConfigSyncRegistry.initialize();
    }

    private static void onRegisterPayload(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(LiteConfigCommon.MOD_ID)
            .optional().executesOn(HandlerThread.MAIN);

        registrar.playToClient(
            ConfigSyncHandshakeS2CPacket.TYPE,
            ConfigSyncHandshakeS2CPacket.STREAM_CODEC,
            (payload, context) -> {
                if (ClientConfigSync.hasRemoteConnection()) {
                    ClientConfigSync.receiveHandshake(payload);
                }
            });

        registrar.playToServer(
            ConfigSyncRequestC2SPacket.TYPE,
            ConfigSyncRequestC2SPacket.STREAM_CODEC,
            (request, context) -> {
                ServerPlayer player = (ServerPlayer) context.player();
                ServerConfigSync.payloadsFor(request)
                    .forEach(payload -> send(player, payload));
            });

        registrar.playToClient(
            ConfigSyncS2CPacket.TYPE,
            ConfigSyncS2CPacket.STREAM_CODEC,
            (payload, context) -> {
                if (ClientConfigSync.hasRemoteConnection()) {
                    ClientConfigSync.receivePayload(payload);
                }
            });
    }

    private static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (LiteConfigCommon.ACTIVE_SERVER == null
            || !(event.getEntity() instanceof ServerPlayer player)
            || !player.connection.hasChannel(ConfigSyncHandshakeS2CPacket.TYPE)) {
            return;
        }

        ServerConfigSync.beginHandshake().forEach(payload -> send(player, payload));
    }

    private static void onServerStarted(ServerStartedEvent event) {
        LiteConfigCommon.ACTIVE_SERVER = event.getServer();
        ConfigEventThreads.setServerMainThread(event.getServer());
        ServerConfigSync.setServerMainThreadExecutor(event.getServer());
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        if (LiteConfigCommon.ACTIVE_SERVER == event.getServer()) {
            LiteConfigCommon.ACTIVE_SERVER = null;
        }

        ConfigEventThreads.clearServerMainThread(event.getServer());
        ServerConfigSync.clearServerMainThreadExecutor(event.getServer());
    }

    private static void scheduleBroadcast(List<ConfigSyncS2CPacket> payloads) {
        MinecraftServer server = LiteConfigCommon.ACTIVE_SERVER;

        server.execute(() -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                payloads.forEach(payload -> send(player, payload));
            }
        });
    }

    private static void scheduleManifest() {
        MinecraftServer server = LiteConfigCommon.ACTIVE_SERVER;

        server.execute(() -> {
            List<ServerPlayer> players = server.getPlayerList().getPlayers().stream()
                .filter(player -> player.connection.hasChannel(ConfigSyncHandshakeS2CPacket.TYPE))
                .toList();

            if (players.isEmpty()) {
                return;
            }

            List<ConfigSyncHandshakeS2CPacket> payloads = ServerConfigSync.beginHandshake();
            for (ServerPlayer player : players) {
                payloads.forEach(payload -> send(player, payload));
            }
        });
    }

    private static <T extends CustomPacketPayload> void send(ServerPlayer player, T payload) {
        if (player.connection.hasChannel(payload)) {
            PacketDistributor.sendToPlayer(player, payload);
        }
    }
}
*///?}
