package com.gmalvestiti.minecraft.liteconfig.platform.fabric;

//? if fabric {
import com.gmalvestiti.minecraft.liteconfig.network.ClientConfigSync;
import com.gmalvestiti.minecraft.liteconfig.network.packet.ConfigSyncHandshakeS2CPacket;
import com.gmalvestiti.minecraft.liteconfig.network.packet.ConfigSyncS2CPacket;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;

public class FabricClientEntrypoint implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ClientLifecycleEvents.CLIENT_STARTED.register(FabricClientEntrypoint::onClientStarted);

        ClientPlayNetworking.registerGlobalReceiver(ConfigSyncHandshakeS2CPacket.TYPE, (payload, context) -> {
            if (ClientConfigSync.isCurrentConnection(context.player().connection.getConnection())) {
                ClientConfigSync.receiveHandshake(payload);
            }
        });

        ClientPlayNetworking.registerGlobalReceiver(ConfigSyncS2CPacket.TYPE, (payload, context) -> {
            if (ClientConfigSync.isCurrentConnection(context.player().connection.getConnection())) {
                ClientConfigSync.receivePayload(payload);
            }
        });

        ClientPlayConnectionEvents.DISCONNECT.register(
            (handler, client) -> ClientConfigSync.resetClientConnection());
    }

    private static void onClientStarted(Minecraft client) {
        ClientConfigSync.bind(new FabricClientPlatform(client));
    }
}
//?}
