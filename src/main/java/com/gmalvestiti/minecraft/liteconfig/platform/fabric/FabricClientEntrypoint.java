package com.gmalvestiti.minecraft.liteconfig.platform.fabric;

//? if fabric {
import com.gmalvestiti.minecraft.liteconfig.network.ClientConfigSync;
import com.gmalvestiti.minecraft.liteconfig.engine.ConfigEventThreads;
import com.gmalvestiti.minecraft.liteconfig.network.packet.ConfigSyncHandshakeS2CPacket;
import com.gmalvestiti.minecraft.liteconfig.network.packet.ConfigSyncRequestC2SPacket;
import com.gmalvestiti.minecraft.liteconfig.network.packet.ConfigSyncS2CPacket;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public class FabricClientEntrypoint implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ClientLifecycleEvents.CLIENT_STARTED.register(FabricClientEntrypoint::onClientStarted);

        ClientPlayNetworking.registerGlobalReceiver(ConfigSyncHandshakeS2CPacket.TYPE, (payload, context) -> {
            if (ClientConfigSync.hasRemoteConnection()) {
                ClientConfigSync.receiveHandshake(payload);
            }
        });

        ClientPlayNetworking.registerGlobalReceiver(ConfigSyncS2CPacket.TYPE, (payload, context) -> {
            context.client().execute(() -> {
                if (ClientConfigSync.hasRemoteConnection()) {
                    ClientConfigSync.receivePayload(payload);
                }
            });
        });

        ClientPlayConnectionEvents.DISCONNECT.register(
            (handler, client) -> ClientConfigSync.resetClientConnection());
    }

    private static void onClientStarted(Minecraft client) {
        ConfigEventThreads.setClientMainThread(client);

        ClientConfigSync.setRemoteConnectionCheck(
            () -> !client.isLocalServer() && client.getConnection() != null);

        ClientConfigSync.setRequestScheduler(request -> {
            if (!ClientConfigSync.hasRemoteConnection()
                || !ClientPlayNetworking.canSend(ConfigSyncRequestC2SPacket.TYPE)) {
                return;
            }

            ClientPlayNetworking.send(request);
        });

        ClientConfigSync.setDisconnectScheduler(reason -> {
            if (client.getConnection() != null) {
                client.getConnection().getConnection().disconnect(Component.translatable(reason));
            }
        });

        ClientConfigSync.setClientMainThreadExecutor(client);
    }
}
//?}
