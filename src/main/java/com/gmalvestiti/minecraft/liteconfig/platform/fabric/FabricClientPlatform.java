package com.gmalvestiti.minecraft.liteconfig.platform.fabric;

//? if fabric {
import com.gmalvestiti.minecraft.liteconfig.network.packet.ConfigSyncRequestC2SPacket;
import com.gmalvestiti.minecraft.liteconfig.platform.CommonClientPlatform;
import com.gmalvestiti.minecraft.liteconfig.platform.ConnectionType;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

final class FabricClientPlatform extends CommonClientPlatform {

    FabricClientPlatform(Minecraft client) {
        super(client);
    }

    @Override
    public void request(ConfigSyncRequestC2SPacket packet) {
        if (connectionType() == ConnectionType.REMOTE && ClientPlayNetworking.canSend(ConfigSyncRequestC2SPacket.TYPE)) {
            ClientPlayNetworking.send(packet);
        }
    }
}
//?}
