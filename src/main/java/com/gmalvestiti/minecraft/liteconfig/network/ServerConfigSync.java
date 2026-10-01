package com.gmalvestiti.minecraft.liteconfig.network;

import com.gmalvestiti.minecraft.liteconfig.LiteConfigCommon;
import com.gmalvestiti.minecraft.liteconfig.network.packet.ConfigSyncHandshakeS2CPacket;
import com.gmalvestiti.minecraft.liteconfig.network.packet.ConfigSyncRequestC2SPacket;
import com.gmalvestiti.minecraft.liteconfig.network.packet.ConfigSyncS2CPacket;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class ServerConfigSync {

    private static final AtomicLong NEXT_TRANSACTION_ID = new AtomicLong();
    private static volatile Executor SERVER_MAIN_THREAD_EXECUTOR;
    private static volatile Consumer<List<ConfigSyncS2CPacket>> BROADCAST_SCHEDULER = ignored -> {};
    private static volatile Runnable MANIFEST_SCHEDULER = () -> {};

    private ServerConfigSync() {}

    public static void setServerMainThreadExecutor(Executor executor) {
        SERVER_MAIN_THREAD_EXECUTOR = Objects.requireNonNull(executor, "executor");
        ConfigSyncRegistry.activate();
    }

    public static void clearServerMainThreadExecutor(Executor executor) {
        if (SERVER_MAIN_THREAD_EXECUTOR == executor) {
            SERVER_MAIN_THREAD_EXECUTOR = null;
        }
    }

    static Executor serverMainThreadExecutor() {
        return SERVER_MAIN_THREAD_EXECUTOR;
    }

    public static void setBroadcastScheduler(Consumer<List<ConfigSyncS2CPacket>> scheduler) {
        BROADCAST_SCHEDULER = Objects.requireNonNull(scheduler, "scheduler");
    }

    public static void setManifestScheduler(Runnable scheduler) {
        MANIFEST_SCHEDULER = Objects.requireNonNull(scheduler, "scheduler");
    }

    static void refreshManifest() {
        Executor executor = SERVER_MAIN_THREAD_EXECUTOR;
        if (executor != null) {
            executor.execute(() -> MANIFEST_SCHEDULER.run());
        }
    }

    public static List<ConfigSyncHandshakeS2CPacket> beginHandshake() {
        if (ConfigSyncRegistry.values().isEmpty()) {
            return List.of();
        }

        Map<String, ConfigBytes> hashes = new TreeMap<>();
        for (SyncedConfig<?> synced : ConfigSyncRegistry.values()) {
            if (synced.ready()) {
                hashes.put(synced.id(), synced.cachedSnapshot().hash());
            }
        }

        if (hashes.isEmpty()) {
            return List.of();
        }

        if (hashes.size() > ConfigSyncProtocol.MAX_MANIFEST_ENTRIES) {
            IllegalStateException failure = new IllegalStateException(
                "Config sync manifest exceeds " + ConfigSyncProtocol.MAX_MANIFEST_ENTRIES + " entries");
            LiteConfigCommon.error("Failed to build config sync manifest", failure);
            throw failure;
        }

        List<Map<String, ConfigBytes>> batches = ConfigPayloads.batches(hashes);
        List<ConfigSyncHandshakeS2CPacket> packets = new ArrayList<>(batches.size());

        for (Map<String, ConfigBytes> batch : batches) {
            packets.add(new ConfigSyncHandshakeS2CPacket(batch));
        }

        return packets;
    }

    public static List<ConfigSyncS2CPacket> payloadsFor(ConfigSyncRequestC2SPacket request) {
        if (request.configIds().isEmpty() || ConfigSyncRegistry.values().isEmpty()) {
            return List.of();
        }

        Map<String, ConfigBytes> configs = new TreeMap<>();
        for (String id : request.configIds()) {

            SyncedConfig<?> synced = ConfigSyncRegistry.get(id);

            if (synced != null && synced.ready()) {
                configs.put(id, synced.cachedSnapshot().data());
            }
        }

        return packets(configs);
    }

    static void broadcast(SyncedConfig<?> synced) {
        Executor executor = SERVER_MAIN_THREAD_EXECUTOR;

        if (executor == null) {
            return;
        }

        executor.execute(() -> {
            if (ConfigSyncRegistry.get(synced.id()) != synced) {
                return;
            }

            SyncedConfig.Snapshot snapshot = synced.changedSnapshot();

            if (snapshot != null) {
                BROADCAST_SCHEDULER.accept(packets(Map.of(synced.id(), snapshot.data())));
            }
        });
    }

    private static List<ConfigSyncS2CPacket> packets(Map<String, ConfigBytes> configs) {
        if (configs.isEmpty()) {
            return List.of();
        }

        List<Map<String, ConfigBytes>> batches = ConfigPayloads.batches(configs);

        if (batches.size() > ConfigSyncProtocol.MAX_TRANSACTION_PACKETS) {
            IllegalArgumentException failure = new IllegalArgumentException(
                "Config sync transaction exceeds " + ConfigSyncProtocol.MAX_TRANSACTION_PACKETS + " packets");
            LiteConfigCommon.error("Failed to build config sync transaction", failure);
            throw failure;
        }

        List<ConfigSyncS2CPacket> packets = new ArrayList<>(batches.size());
        long transactionId = NEXT_TRANSACTION_ID.getAndIncrement();

        for (int index = 0; index < batches.size(); index++) {
            packets.add(new ConfigSyncS2CPacket(
                transactionId, index == batches.size() - 1, batches.get(index)));
        }

        return packets;
    }
}
