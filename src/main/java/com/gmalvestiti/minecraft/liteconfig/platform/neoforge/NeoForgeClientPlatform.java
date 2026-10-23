package com.gmalvestiti.minecraft.liteconfig.platform.neoforge;

//? if neoforge {
/*import com.gmalvestiti.minecraft.liteconfig.network.packet.ConfigSyncRequestC2SPacket;
import com.gmalvestiti.minecraft.liteconfig.platform.CommonClientPlatform;
import com.gmalvestiti.minecraft.liteconfig.platform.ConnectionType;
import net.minecraft.client.Minecraft;
//? if >=26.1 {
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
//?}

final class NeoForgeClientPlatform extends CommonClientPlatform {

    NeoForgeClientPlatform(Minecraft client) {
        super(client);
    }

    @Override
    public void request(ConfigSyncRequestC2SPacket packet) {
        if (connectionType() != ConnectionType.REMOTE || !client.getConnection().hasChannel(ConfigSyncRequestC2SPacket.TYPE)) {
            return;
        }
        //? if >=26.1 {
        ClientPacketDistributor.sendToServer(packet);
        //?} else {
        /^client.getConnection().send(packet);
        ^///?}
    }
}
*///?}
