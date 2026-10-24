package com.gmalvestiti.minecraft.liteconfig.platform;

import com.gmalvestiti.minecraft.liteconfig.network.ServerConfigSync;
import com.gmalvestiti.minecraft.liteconfig.network.ConfigSyncRegistry;
import com.gmalvestiti.minecraft.liteconfig.network.packet.ConfigSyncHandshakeS2CPacket;
import com.gmalvestiti.minecraft.liteconfig.network.packet.ConfigSyncS2CPacket;
import com.gmalvestiti.minecraft.liteconfig.registry.ConfigRegistry;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

public abstract class CommonServerPlatform implements Executor {

    private static volatile CommonServerPlatform CURRENT;

    public final MinecraftServer server;
    private volatile boolean ready;

    protected CommonServerPlatform(MinecraftServer server) {
        this.server = Objects.requireNonNull(server, "server");
    }

    public static synchronized void starting(CommonServerPlatform platform) {
        Objects.requireNonNull(platform, "platform").ready = false;
        CURRENT = platform;
        ConfigRegistry.activateWorld(platform.server.getWorldPath(LevelResource.ROOT));
    }

    public static synchronized void started(MinecraftServer server) {
        CommonServerPlatform platform = CURRENT;
        if (platform != null && platform.server == server) {
            bind(platform);
        }
    }

    public static synchronized boolean stopped(MinecraftServer server) {
        if (!unbind(server)) {
            return false;
        }
        ConfigRegistry.deactivateWorld();
        return true;
    }

    public static synchronized void bind(CommonServerPlatform platform) {
        Objects.requireNonNull(platform, "platform").ready = true;
        CURRENT = platform;
        ConfigSyncRegistry.activate();
    }

    public static synchronized boolean unbind(Object identity) {
        CommonServerPlatform platform = CURRENT;
        if (platform == null || platform.identity() != identity) {
            return false;
        }
        platform.ready = false;
        CURRENT = null;
        return true;
    }

    public static CommonServerPlatform current() {
        CommonServerPlatform platform = CURRENT;
        return platform != null && platform.ready ? platform : null;
    }

    public Object identity() {
        return server;
    }

    @Override
    public void execute(Runnable task) {
        if (ServerConfigSync.isCurrent(this)) {
            server.execute(() -> {
                if (ServerConfigSync.isCurrent(this)) {
                    task.run();
                }
            });
        }
    }

    public void broadcast(Supplier<List<ConfigSyncS2CPacket>> packets) {
        execute(() -> {
            List<ServerPlayer> players = server.getPlayerList().getPlayers().stream()
                .filter(player -> canSend(player, ConfigSyncS2CPacket.TYPE))
                .toList();

            if (players.isEmpty()) {
                return;
            }

            List<ConfigSyncS2CPacket> payloads = packets.get();
            players.forEach(player -> payloads.forEach(packet -> send(player, packet)));
        });
    }

    public void refreshManifest() {
        execute(() -> {
            List<ServerPlayer> players = server.getPlayerList().getPlayers().stream()
                .filter(player -> canSend(player, ConfigSyncHandshakeS2CPacket.TYPE)).toList();

            if (!players.isEmpty()) {
                List<ConfigSyncHandshakeS2CPacket> packets = ServerConfigSync.beginHandshake(false);
                players.forEach(player -> packets.forEach(packet -> send(player, packet)));
            }
        });
    }

    protected abstract boolean canSend(ServerPlayer player, CustomPacketPayload.Type<?> type);

    protected abstract void send(ServerPlayer player, CustomPacketPayload packet);
}
