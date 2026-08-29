package com.gmalvestiti.minecraft.liteconfig.platform.fabric;

//? if fabric {
import com.gmalvestiti.minecraft.liteconfig.network.ConfigSyncRegistry;
import com.gmalvestiti.minecraft.liteconfig.async.ConfigEventExecutors;
import com.gmalvestiti.minecraft.liteconfig.network.packet.ConfigSyncHandshakeS2CPacket;
import com.gmalvestiti.minecraft.liteconfig.network.packet.ConfigSyncRequestC2SPacket;
import com.gmalvestiti.minecraft.liteconfig.network.packet.ConfigSyncS2CPacket;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public class FabricClientEntrypoint implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ConfigEventExecutors.setClientMainThread(Minecraft.getInstance());

        ClientPlayNetworking.registerGlobalReceiver(ConfigSyncHandshakeS2CPacket.TYPE, (payload, context) -> {
            if (ConfigSyncRegistry.hasRemoteConnection()) {
                ConfigSyncRegistry.receiveHandshake(payload);
            }
        });

        ClientPlayNetworking.registerGlobalReceiver(ConfigSyncS2CPacket.TYPE, (payload, context) -> {
            if (ConfigSyncRegistry.hasRemoteConnection()) {
                ConfigSyncRegistry.receiveResultAsync(payload)
                    .thenAccept(ConfigSyncRegistry::handleClientResult);
            }
        });

        ConfigSyncRegistry.setClientMainThreadExecutor(
            task -> Minecraft.getInstance().execute(task));

        ConfigSyncRegistry.setRemoteConnectionCheck(() -> {
            Minecraft client = Minecraft.getInstance();
            return !client.isLocalServer() && client.getConnection() != null;
        });

        ConfigSyncRegistry.setRequestScheduler(request -> {
            Minecraft client = Minecraft.getInstance();
            if (ConfigSyncRegistry.hasRemoteConnection()
                && ClientPlayNetworking.canSend(ConfigSyncRequestC2SPacket.TYPE)) {
                ClientPlayNetworking.send(request);
            }
        });

        ConfigSyncRegistry.setDisconnectScheduler(reason -> {
            Minecraft client = Minecraft.getInstance();
            if (client.getConnection() != null) {
                client.getConnection().getConnection().disconnect(Component.translatable(reason));
            }
        });

        ClientPlayConnectionEvents.DISCONNECT.register(
            (handler, client) -> ConfigSyncRegistry.resetClientConnection());
    }
}
//?}
