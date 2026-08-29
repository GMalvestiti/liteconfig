package com.gmalvestiti.minecraft.liteconfig.platform.fabric;

//? if fabric {
import com.gmalvestiti.minecraft.liteconfig.async.ConfigEventExecutors;
import com.gmalvestiti.minecraft.liteconfig.network.ConfigSyncRegistry;
import com.gmalvestiti.minecraft.liteconfig.network.packet.ConfigSyncHandshakeS2CPacket;
import com.gmalvestiti.minecraft.liteconfig.network.packet.ConfigSyncRequestC2SPacket;
import com.gmalvestiti.minecraft.liteconfig.network.packet.ConfigSyncS2CPacket;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public class FabricEntrypoint implements ModInitializer {

    private static volatile MinecraftServer activeServer;

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.clientboundPlay().register(
            ConfigSyncHandshakeS2CPacket.TYPE, ConfigSyncHandshakeS2CPacket.STREAM_CODEC);

        PayloadTypeRegistry.serverboundPlay().register(
            ConfigSyncRequestC2SPacket.TYPE, ConfigSyncRequestC2SPacket.STREAM_CODEC);

        PayloadTypeRegistry.clientboundPlay().register(
            ConfigSyncS2CPacket.TYPE, ConfigSyncS2CPacket.STREAM_CODEC);

        ServerPlayNetworking.registerGlobalReceiver(ConfigSyncRequestC2SPacket.TYPE, (request, context) -> {
            for (ConfigSyncS2CPacket payload : ConfigSyncRegistry.payloadsFor(request)) {
                ServerPlayNetworking.send(context.player(), payload);
            }
        });

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            activeServer = server;
            ConfigEventExecutors.setServerMainThread(server);
        });

        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            if (activeServer == server) {
                activeServer = null;
            }
            ConfigEventExecutors.clearServerMainThread(server);
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, joinedServer) -> {
            if (ServerPlayNetworking.canSend(handler.getPlayer(), ConfigSyncHandshakeS2CPacket.TYPE)) {
                for (ConfigSyncHandshakeS2CPacket payload : ConfigSyncRegistry.beginHandshake()) {
                    ServerPlayNetworking.send(handler.getPlayer(), payload);
                }
            }
        });

        ConfigSyncRegistry.setBroadcastScheduler((payloads) -> {
            MinecraftServer server = activeServer;
            if (server == null) {
                return;
            }
            server.execute(() -> {
                for (ServerPlayer player : PlayerLookup.all(server)) {
                    if (ServerPlayNetworking.canSend(player, ConfigSyncS2CPacket.TYPE)) {
                        payloads.forEach(payload -> ServerPlayNetworking.send(player, payload));
                    }
                }
            });
        });

        ConfigSyncRegistry.setManifestScheduler(() -> {
            MinecraftServer server = activeServer;
            if (server == null) {
                return;
            }
            server.execute(() -> {
                for (ServerPlayer player : PlayerLookup.all(server)) {
                    if (ServerPlayNetworking.canSend(player, ConfigSyncHandshakeS2CPacket.TYPE)) {
                        ConfigSyncRegistry.beginHandshake().forEach(
                            payload -> ServerPlayNetworking.send(player, payload));
                    }
                }
            });
        });
    }
}
//?}
