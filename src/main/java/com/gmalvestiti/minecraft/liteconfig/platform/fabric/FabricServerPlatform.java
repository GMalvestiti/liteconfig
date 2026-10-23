package com.gmalvestiti.minecraft.liteconfig.platform.fabric;

//? if fabric {
import com.gmalvestiti.minecraft.liteconfig.platform.CommonServerPlatform;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

final class FabricServerPlatform extends CommonServerPlatform {

    FabricServerPlatform(MinecraftServer server) {
        super(server);
    }

    @Override
    protected boolean canSend(ServerPlayer player, CustomPacketPayload.Type<?> type) {
        return ServerPlayNetworking.canSend(player, type);
    }

    @Override
    protected void send(ServerPlayer player, CustomPacketPayload packet) {
        ServerPlayNetworking.send(player, packet);
    }
}
//?}
