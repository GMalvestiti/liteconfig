package com.gmalvestiti.minecraft.liteconfig.platform.fabric;

//? if fabric {
import com.gmalvestiti.minecraft.liteconfig.platform.CommonServerPlatform;
import com.gmalvestiti.minecraft.liteconfig.network.ConfigSyncRegistry;
import com.gmalvestiti.minecraft.liteconfig.network.ServerConfigSync;
import com.gmalvestiti.minecraft.liteconfig.network.packet.ConfigSyncHandshakeS2CPacket;
import com.gmalvestiti.minecraft.liteconfig.network.packet.ConfigSyncRequestC2SPacket;
import com.gmalvestiti.minecraft.liteconfig.network.packet.ConfigSyncS2CPacket;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

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
            if (!ServerConfigSync.isCurrentServer(context.server())) {
                return;
            }

            for (ConfigSyncS2CPacket payload : ServerConfigSync.payloadsFor(request)) {
                ServerPlayNetworking.send(context.player(), payload);
            }
        });

        ServerLifecycleEvents.SERVER_STARTING.register(
            server -> CommonServerPlatform.starting(new FabricServerPlatform(server)));

        ServerLifecycleEvents.SERVER_STARTED.register(CommonServerPlatform::started);

        ServerLifecycleEvents.SERVER_STOPPED.register(CommonServerPlatform::stopped);

        ServerPlayConnectionEvents.JOIN.register((handler, sender, joinedServer) -> {
            if (!ServerConfigSync.isCurrentServer(joinedServer)
                || !ServerPlayNetworking.canSend(handler.getPlayer(), ConfigSyncHandshakeS2CPacket.TYPE)) {
                return;
            }

            for (ConfigSyncHandshakeS2CPacket payload : ServerConfigSync.beginHandshake(true)) {
                ServerPlayNetworking.send(handler.getPlayer(), payload);
            }
        });
    }
}
//?}
