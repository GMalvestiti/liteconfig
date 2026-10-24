package com.gmalvestiti.minecraft.liteconfig.network;

import com.gmalvestiti.minecraft.liteconfig.LiteConfigCommon;
import com.gmalvestiti.minecraft.liteconfig.network.packet.ConfigSyncHandshakeS2CPacket;
import com.gmalvestiti.minecraft.liteconfig.network.packet.ConfigSyncRequestC2SPacket;
import com.gmalvestiti.minecraft.liteconfig.network.packet.ConfigSyncS2CPacket;
import com.gmalvestiti.minecraft.liteconfig.platform.CommonServerPlatform;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

public final class ServerConfigSync {

    private static final AtomicLong NEXT_TRANSACTION_ID = new AtomicLong();

    private ServerConfigSync() {
    }

    public static boolean isCurrent(CommonServerPlatform platform) {
        return CommonServerPlatform.current() == platform;
    }

    public static boolean isCurrentServer(Object server) {
        CommonServerPlatform platform = CommonServerPlatform.current();
        return platform != null && platform.identity() == server;
    }

    static Executor serverMainThreadExecutor() {
        return CommonServerPlatform.current();
    }

    public static void refreshManifest() {
        CommonServerPlatform platform = CommonServerPlatform.current();
        if (platform != null) {
            platform.refreshManifest();
        }
    }

    public static List<ConfigSyncHandshakeS2CPacket> beginHandshake(boolean joining) {
        if (ConfigSyncRegistry.values().isEmpty()) {
            return List.of();
        }

        Map<String, ConfigBytes> hashes = new TreeMap<>();
        for (SyncedConfig<?> synced : ConfigSyncRegistry.values()) {
            if (synced.ready() && synced.locallyActive()) {
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
            packets.add(new ConfigSyncHandshakeS2CPacket(joining, batch));
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

            if (synced != null && synced.ready() && synced.locallyActive()) {
                configs.put(id, synced.cachedSnapshot().data());
            }
        }

        return packets(configs, request.joining());
    }

    static void scheduleBroadcast(SyncedConfig<?> synced) {
        CommonServerPlatform platform = CommonServerPlatform.current();

        if (platform == null || !synced.locallyActive()) {
            return;
        }

        String syncId = synced.id();
        WeakReference<SyncedConfig<?>> reference = new WeakReference<>(synced);

        platform.broadcast(() -> {
            SyncedConfig<?> current = reference.get();

            if (current == null || ConfigSyncRegistry.get(syncId) != current || !current.locallyActive()) {
                return List.of();
            }

            try {
                SyncedConfig.Snapshot snapshot = current.changedSnapshot();
                return snapshot == null ? List.of() : packets(Map.of(syncId, snapshot.data()), false);
            } catch (RuntimeException failure) {
                LiteConfigCommon.error("Failed to build config sync update for " + syncId, failure);
                return List.of();
            }
        });
    }

    private static List<ConfigSyncS2CPacket> packets(Map<String, ConfigBytes> configs, boolean joining) {
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
                transactionId, index == batches.size() - 1, joining, batches.get(index)));
        }

        return packets;
    }
}
