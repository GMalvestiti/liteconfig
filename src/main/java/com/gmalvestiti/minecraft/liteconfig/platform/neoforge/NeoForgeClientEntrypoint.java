package com.gmalvestiti.minecraft.liteconfig.platform.neoforge;

//? if neoforge {
/*import com.gmalvestiti.minecraft.liteconfig.LiteConfigCommon;
import com.gmalvestiti.minecraft.liteconfig.async.ConfigEventExecutors;
import com.gmalvestiti.minecraft.liteconfig.network.ConfigSyncRegistry;
import com.gmalvestiti.minecraft.liteconfig.network.packet.ConfigSyncRequestC2SPacket;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
//? if >=26.1 {
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
//?}

@Mod(value = LiteConfigCommon.MOD_ID, dist = Dist.CLIENT)
public class NeoForgeClientEntrypoint {

    public NeoForgeClientEntrypoint() {
        ConfigEventExecutors.setClientMainThread(Minecraft.getInstance());

        ConfigSyncRegistry.setClientMainThreadExecutor(task -> Minecraft.getInstance().execute(task));

        ConfigSyncRegistry.setRemoteConnectionCheck(() -> {
            Minecraft client = Minecraft.getInstance();
            return !client.isLocalServer() && client.getConnection() != null;
        });

        ConfigSyncRegistry.setDisconnectScheduler(reason -> {
            Minecraft client = Minecraft.getInstance();
            if (client.getConnection() != null) {
                client.getConnection().getConnection().disconnect(Component.translatable(reason));
            }
        });

        ConfigSyncRegistry.setRequestScheduler(NeoForgeClientEntrypoint::sendRequest);

        NeoForge.EVENT_BUS.addListener(NeoForgeClientEntrypoint::onLoggingOut);
    }

    private static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ConfigSyncRegistry.resetClientConnection();
    }

    private static void sendRequest(ConfigSyncRequestC2SPacket request) {
        Minecraft client = Minecraft.getInstance();
        if (!ConfigSyncRegistry.hasRemoteConnection()
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
