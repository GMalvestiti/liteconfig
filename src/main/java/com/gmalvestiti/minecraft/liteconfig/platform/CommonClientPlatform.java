package com.gmalvestiti.minecraft.liteconfig.platform;

import com.gmalvestiti.minecraft.liteconfig.network.packet.ConfigSyncRequestC2SPacket;
import com.gmalvestiti.minecraft.liteconfig.shared.TranslationKey;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;

import java.util.Objects;
import java.util.concurrent.Executor;

public abstract class CommonClientPlatform implements Executor {

    protected final Minecraft client;

    protected CommonClientPlatform(Minecraft client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public void execute(Runnable task) {
        client.execute(task);
    }

    public ConnectionType connectionType() {
        ClientPacketListener listener = client.getConnection();

        if (listener == null) {
            return ConnectionType.DISCONNECTED;
        }

        return client.isLocalServer() ? ConnectionType.INTEGRATED_HOST : ConnectionType.REMOTE;
    }

    public String connectionIdentity() {
        ClientPacketListener listener = Objects.requireNonNull(client.getConnection(), "client connection");

        ServerData serverData = client.getCurrentServer();
        String address = serverData != null ? serverData.ip : null;

        if (address == null || address.isBlank()) {
            address = Objects.requireNonNull(listener.getConnection().getRemoteAddress(),
                "remote address").toString();
        }

        return address;
    }

    public Object connectionToken() {
        ClientPacketListener listener = client.getConnection();
        return listener != null ? listener.getConnection() : null;
    }

    public void disconnect(TranslationKey reason) {
        ClientPacketListener listener = client.getConnection();

        if (listener != null) {
            listener.getConnection().disconnect(Component.translatable(reason.key()).withStyle(
                reason == TranslationKey.RESTART_REQUIRED_SERVER ? ChatFormatting.YELLOW : ChatFormatting.RED));
        }
    }

    public abstract void request(ConfigSyncRequestC2SPacket packet);
}
