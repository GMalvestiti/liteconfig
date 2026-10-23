package com.gmalvestiti.minecraft.liteconfig.platform.neoforge;

//? if neoforge {
/*import com.gmalvestiti.minecraft.liteconfig.LiteConfigCommon;
import com.gmalvestiti.minecraft.liteconfig.platform.CommonServerPlatform;
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
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.HandlerThread;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

@Mod(LiteConfigCommon.MOD_ID)
public class NeoForgeEntrypoint {

    public NeoForgeEntrypoint(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(NeoForgeEntrypoint::onRegisterPayload);

        NeoForge.EVENT_BUS.addListener(NeoForgeEntrypoint::onServerAboutToStart);
        NeoForge.EVENT_BUS.addListener(NeoForgeEntrypoint::onServerStarted);
        NeoForge.EVENT_BUS.addListener(NeoForgeEntrypoint::onServerStopped);
        NeoForge.EVENT_BUS.addListener(NeoForgeEntrypoint::onPlayerLoggedIn);
    }

    private static void onRegisterPayload(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(LiteConfigCommon.MOD_ID)
            .optional().executesOn(HandlerThread.NETWORK);

        registrar.playToClient(
            ConfigSyncHandshakeS2CPacket.TYPE,
            ConfigSyncHandshakeS2CPacket.STREAM_CODEC,
            (payload, context) -> {
                Object connection = context.connection();
                context.enqueueWork(() -> {
                    if (ClientConfigSync.isCurrentConnection(connection)) {
                        ClientConfigSync.receiveHandshake(payload);
                    }
                });
            });

        registrar.playToServer(
            ConfigSyncRequestC2SPacket.TYPE,
            ConfigSyncRequestC2SPacket.STREAM_CODEC,
            (request, context) -> {
                ServerPlayer player = (ServerPlayer) context.player();
                MinecraftServer server = player.level().getServer();

                context.enqueueWork(() -> {
                    if (!ServerConfigSync.isCurrentServer(server)) {
                        return;
                    }

                    ServerConfigSync.payloadsFor(request)
                        .forEach(payload -> send(player, payload));
                });
            });

        registrar.playToClient(
            ConfigSyncS2CPacket.TYPE,
            ConfigSyncS2CPacket.STREAM_CODEC,
            (payload, context) -> {
                Object connection = context.connection();
                context.enqueueWork(() -> {
                    if (ClientConfigSync.isCurrentConnection(connection)) {
                        ClientConfigSync.receivePayload(payload);
                    }
                });
            });
    }

    private static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)
            || !ServerConfigSync.isCurrentServer(player.level().getServer())
            || !player.connection.hasChannel(ConfigSyncHandshakeS2CPacket.TYPE)) {
            return;
        }

        ServerConfigSync.beginHandshake().forEach(payload -> send(player, payload));
    }

    private static void onServerAboutToStart(ServerAboutToStartEvent event) {
        CommonServerPlatform.starting(new NeoForgeServerPlatform(event.getServer()));
    }

    private static void onServerStarted(ServerStartedEvent event) {
        CommonServerPlatform.started(event.getServer());
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        CommonServerPlatform.stopped(event.getServer());
    }

    private static <T extends CustomPacketPayload> void send(ServerPlayer player, T payload) {
        if (player.connection.hasChannel(payload)) {
            PacketDistributor.sendToPlayer(player, payload);
        }
    }
}
*///?}
