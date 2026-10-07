package com.gmalvestiti.minecraft.liteconfig.platform.neoforge;

//? if neoforge {
/*import com.gmalvestiti.minecraft.liteconfig.LiteConfigCommon;
import com.gmalvestiti.minecraft.liteconfig.engine.ConfigEventThreads;
import com.gmalvestiti.minecraft.liteconfig.network.ClientConfigSync;
import com.gmalvestiti.minecraft.liteconfig.network.packet.ConfigSyncRequestC2SPacket;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
//? if >=26.1 {
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
//?}

import java.util.function.Consumer;

@Mod(value = LiteConfigCommon.MOD_ID, dist = Dist.CLIENT)
public class NeoForgeClientEntrypoint {

    private static final Consumer<ClientTickEvent.Post> CLIENT_START =
        NeoForgeClientEntrypoint::onClientStarted;

    public NeoForgeClientEntrypoint(IEventBus modEventBus) {
        NeoForge.EVENT_BUS.addListener(CLIENT_START);
        NeoForge.EVENT_BUS.addListener(NeoForgeClientEntrypoint::onLoggingOut);
    }

    private static void onClientStarted(ClientTickEvent.Post event) {
        NeoForge.EVENT_BUS.unregister(CLIENT_START);

        Minecraft client = Minecraft.getInstance();
        ConfigEventThreads.setClientMainThread(client);

        ClientConfigSync.setRemoteConnectionCheck(
            () -> !client.isLocalServer() && client.getConnection() != null);

        ClientConfigSync.setDisconnectScheduler(reason -> {
            if (client.getConnection() != null) {
                client.getConnection().getConnection().disconnect(Component.translatable(reason));
            }
        });

        ClientConfigSync.setRequestScheduler(NeoForgeClientEntrypoint::sendRequest);
        ClientConfigSync.setClientMainThreadExecutor(client);
    }

    private static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientConfigSync.resetClientConnection();
    }

    private static void sendRequest(ConfigSyncRequestC2SPacket request) {
        Minecraft client = Minecraft.getInstance();

        if (!ClientConfigSync.hasRemoteConnection()
            || !client.getConnection().hasChannel(ConfigSyncRequestC2SPacket.TYPE)) {
            return;
        }

        //? if >=26.1 {
        ClientPacketDistributor.sendToServer(request);
        //?} else {
        /^client.getConnection().send(request);
        ^///?}
    }
}
*///?}
