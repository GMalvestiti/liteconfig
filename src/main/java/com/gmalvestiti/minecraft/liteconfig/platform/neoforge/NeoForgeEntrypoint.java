package com.gmalvestiti.minecraft.liteconfig.platform.neoforge;

//? if neoforge {
/*import com.gmalvestiti.minecraft.liteconfig.LiteConfigCommon;
import com.gmalvestiti.minecraft.liteconfig.async.ConfigEventExecutors;
import com.gmalvestiti.minecraft.liteconfig.network.ConfigSyncRegistry;
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
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

@Mod(LiteConfigCommon.MOD_ID)
public class NeoForgeEntrypoint {

    private static volatile MinecraftServer activeServer;

    public NeoForgeEntrypoint(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(NeoForgeEntrypoint::onRegisterPayload);

        NeoForge.EVENT_BUS.addListener(NeoForgeEntrypoint::onServerStarted);
        NeoForge.EVENT_BUS.addListener(NeoForgeEntrypoint::onServerStopped);
        NeoForge.EVENT_BUS.addListener(NeoForgeEntrypoint::onPlayerLoggedIn);

        ConfigSyncRegistry.setBroadcastScheduler(NeoForgeEntrypoint::scheduleBroadcast);
        ConfigSyncRegistry.setManifestScheduler(NeoForgeEntrypoint::scheduleManifest);
    }

    private static void onRegisterPayload(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(LiteConfigCommon.MOD_ID).optional();

        registrar.playToClient(
            ConfigSyncHandshakeS2CPacket.TYPE,
            ConfigSyncHandshakeS2CPacket.STREAM_CODEC,
            (payload, context) -> context.enqueueWork(() -> {
                if (ConfigSyncRegistry.hasRemoteConnection()) {
                    ConfigSyncRegistry.receiveHandshake(payload);
                }
            }));

        registrar.playToServer(
            ConfigSyncRequestC2SPacket.TYPE,
            ConfigSyncRequestC2SPacket.STREAM_CODEC,
            (request, context) -> context.enqueueWork(() -> {
                ServerPlayer player = (ServerPlayer) context.player();
                ConfigSyncRegistry.payloadsFor(request)
                    .forEach(payload -> send(player, payload));
            }));

        registrar.playToClient(
            ConfigSyncS2CPacket.TYPE,
            ConfigSyncS2CPacket.STREAM_CODEC,
            (payload, context) -> context.enqueueWork(() -> {
                if (ConfigSyncRegistry.hasRemoteConnection()) {
                    ConfigSyncRegistry.receiveResultAsync(payload)
                        .thenAccept(ConfigSyncRegistry::handleClientResult);
                }
            }));
    }

    private static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ConfigSyncRegistry.beginHandshake().forEach(payload -> send(player, payload));
        }
    }

    private static void onServerStarted(ServerStartedEvent event) {
        activeServer = event.getServer();
        ConfigEventExecutors.setServerMainThread(event.getServer());
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        if (activeServer == event.getServer()) {
            activeServer = null;
        }
        ConfigEventExecutors.clearServerMainThread(event.getServer());
    }

    private static void scheduleBroadcast(java.util.List<ConfigSyncS2CPacket> payloads) {
        MinecraftServer server = activeServer;
        if (server == null) {
            return;
        }
        server.execute(() -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                payloads.forEach(payload -> send(player, payload));
            }
        });
    }

    private static void scheduleManifest() {
        MinecraftServer server = activeServer;
        if (server == null) {
            return;
        }
        server.execute(() -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                ConfigSyncRegistry.beginHandshake()
                    .forEach(payload -> send(player, payload));
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
