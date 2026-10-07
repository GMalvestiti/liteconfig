package com.gmalvestiti.minecraft.liteconfig.platform.fabric;

//? if fabric {
import com.gmalvestiti.minecraft.liteconfig.LiteConfigCommon;
import com.gmalvestiti.minecraft.liteconfig.engine.ConfigEventThreads;
import com.gmalvestiti.minecraft.liteconfig.network.ConfigSyncRegistry;
import com.gmalvestiti.minecraft.liteconfig.network.ServerConfigSync;
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

import java.util.List;

public class FabricEntrypoint implements ModInitializer {

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.clientboundPlay().register(
            ConfigSyncHandshakeS2CPacket.TYPE, ConfigSyncHandshakeS2CPacket.STREAM_CODEC);

        PayloadTypeRegistry.serverboundPlay().register(
            ConfigSyncRequestC2SPacket.TYPE, ConfigSyncRequestC2SPacket.STREAM_CODEC);

        PayloadTypeRegistry.clientboundPlay().register(
            ConfigSyncS2CPacket.TYPE, ConfigSyncS2CPacket.STREAM_CODEC);

        ServerPlayNetworking.registerGlobalReceiver(ConfigSyncRequestC2SPacket.TYPE, (request, context) -> {
            for (ConfigSyncS2CPacket payload : ServerConfigSync.payloadsFor(request)) {
                ServerPlayNetworking.send(context.player(), payload);
            }
        });

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            LiteConfigCommon.ACTIVE_SERVER = server;
            ConfigEventThreads.setServerMainThread(server);
            ServerConfigSync.setServerMainThreadExecutor(server);
        });

        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            if (LiteConfigCommon.ACTIVE_SERVER == server) {
                LiteConfigCommon.ACTIVE_SERVER = null;
            }

            ConfigEventThreads.clearServerMainThread(server);
            ServerConfigSync.clearServerMainThreadExecutor(server);
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, joinedServer) -> {
            if (LiteConfigCommon.ACTIVE_SERVER != joinedServer
                || !ServerPlayNetworking.canSend(handler.getPlayer(), ConfigSyncHandshakeS2CPacket.TYPE)) {
                return;
            }

            for (ConfigSyncHandshakeS2CPacket payload : ServerConfigSync.beginHandshake()) {
                ServerPlayNetworking.send(handler.getPlayer(), payload);
            }
        });

        ServerConfigSync.setBroadcastScheduler((payloads) -> {
            MinecraftServer server = LiteConfigCommon.ACTIVE_SERVER;

            server.execute(() -> {
                for (ServerPlayer player : PlayerLookup.all(server)) {
                    if (ServerPlayNetworking.canSend(player, ConfigSyncS2CPacket.TYPE)) {
                        payloads.forEach(payload -> ServerPlayNetworking.send(player, payload));
                    }
                }
            });
        });

        ServerConfigSync.setManifestScheduler(() -> {
            MinecraftServer server = LiteConfigCommon.ACTIVE_SERVER;

            server.execute(() -> {
                List<ServerPlayer> players = PlayerLookup.all(server).stream()
                    .filter(player -> ServerPlayNetworking.canSend(player, ConfigSyncHandshakeS2CPacket.TYPE))
                    .toList();

                if (players.isEmpty()) {
                    return;
                }

                List<ConfigSyncHandshakeS2CPacket> payloads = ServerConfigSync.beginHandshake();
                for (ServerPlayer player : players) {
                    payloads.forEach(payload -> ServerPlayNetworking.send(player, payload));
                }
            });
        });

        ConfigSyncRegistry.initialize();
    }
}
//?}
