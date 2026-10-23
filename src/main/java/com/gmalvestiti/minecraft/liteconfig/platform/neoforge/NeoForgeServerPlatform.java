package com.gmalvestiti.minecraft.liteconfig.platform.neoforge;

//? if neoforge {
/*import com.gmalvestiti.minecraft.liteconfig.platform.CommonServerPlatform;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

final class NeoForgeServerPlatform extends CommonServerPlatform {

    NeoForgeServerPlatform(MinecraftServer server) {
        super(server);
    }

    @Override
    protected boolean canSend(ServerPlayer player, CustomPacketPayload.Type<?> type) {
        return player.connection.hasChannel(type);
    }

    @Override
    protected void send(ServerPlayer player, CustomPacketPayload packet) {
        PacketDistributor.sendToPlayer(player, packet);
    }
}
*///?}
