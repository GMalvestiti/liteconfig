package com.gmalvestiti.minecraft.liteconfig.network;

import com.gmalvestiti.minecraft.liteconfig.LiteConfigCommon;
import com.gmalvestiti.minecraft.liteconfig.network.packet.ConfigSyncHandshakeS2CPacket;
import com.gmalvestiti.minecraft.liteconfig.network.packet.ConfigSyncRequestC2SPacket;
import com.gmalvestiti.minecraft.liteconfig.network.packet.ConfigSyncS2CPacket;
import com.gmalvestiti.minecraft.liteconfig.platform.CommonClientPlatform;
import com.gmalvestiti.minecraft.liteconfig.platform.ConnectionType;
import com.gmalvestiti.minecraft.liteconfig.shared.TranslationKey;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;

import static com.gmalvestiti.minecraft.liteconfig.network.ConfigSyncProtocol.ENTRIES_PER_PACKET;

public final class ClientConfigSync {

    private static final Map<String, ConfigBytes> SERVER_CONFIG_HASHES = new HashMap<>();
    private static final ConfigSyncTransactions TRANSACTIONS =
        new ConfigSyncTransactions(ConfigSyncRegistry::get);

    private static volatile CommonClientPlatform PLATFORM;

    private ClientConfigSync() {}

    public static void bind(CommonClientPlatform platform) {
        PLATFORM = Objects.requireNonNull(platform, "platform");
        ConfigSyncRegistry.activate();
    }

    static Executor availableMainThreadExecutor() {
        return PLATFORM;
    }

    public static boolean hasRemoteConnection() {
        CommonClientPlatform platform = PLATFORM;
        return platform != null && platform.connectionType() == ConnectionType.REMOTE;
    }

    public static boolean isCurrentConnection(Object connection) {
        CommonClientPlatform platform = PLATFORM;
        return connection != null && platform != null
            && platform.connectionToken() == connection && platform.connectionType() == ConnectionType.REMOTE;
    }

    static String connectionIdentity() {
        CommonClientPlatform platform = Objects.requireNonNull(PLATFORM, "client platform");
        return Objects.requireNonNull(platform.connectionIdentity(), "connection identity");
    }

    public static void receiveHandshake(ConfigSyncHandshakeS2CPacket handshake) {
        try {
            for (Map.Entry<String, ConfigBytes> entry : handshake.hashes().entrySet()) {
                if (!SERVER_CONFIG_HASHES.containsKey(entry.getKey())
                    && SERVER_CONFIG_HASHES.size() == ConfigSyncProtocol.MAX_MANIFEST_ENTRIES) {
                    throw new IllegalArgumentException("Config sync manifest exceeds connection entry limit");
                }

                SERVER_CONFIG_HASHES.put(entry.getKey(), entry.getValue());
            }

            synchronize(handshake.hashes());
        } catch (RuntimeException failure) {
            rejectHandshake(failure);
        }
    }

    public static ClientSyncResult receivePayload(ConfigSyncS2CPacket payload) {
        ClientSyncResult result = TRANSACTIONS.receive(payload);

        if (result.disconnectReason() != null) {
            disconnect(result.disconnectReason());
        }

        return result;
    }

    public static void resetClientConnection() {
        SERVER_CONFIG_HASHES.clear();
        TRANSACTIONS.clear();
        ConfigSyncRegistry.values().forEach(SyncedConfig::clearRemote);
    }

    static void requestIfAdvertised(SyncedConfig<?> synced) {
        ConfigBytes hash = SERVER_CONFIG_HASHES.get(synced.id());

        if (hash != null) {
            try {
                synchronize(Map.of(synced.id(), hash));
            } catch (RuntimeException failure) {
                rejectHandshake(failure);
            }
        }
    }

    private static void synchronize(Map<String, ConfigBytes> hashes) {
        Map<String, ConfigBytes> matching = new LinkedHashMap<>();
        List<String> requested = new ArrayList<>();

        int matchingBytes = 0;
        for (Map.Entry<String, ConfigBytes> entry : hashes.entrySet()) {

            SyncedConfig<?> synced = ConfigSyncRegistry.get(entry.getKey());
            if (synced == null) {
                continue;
            }

            ConfigBytes payload = synced.payloadMatching(entry.getValue());

            if (payload == null) {
                requested.add(entry.getKey());
            } else {
                matchingBytes += payload.size();

                if (matchingBytes > ConfigSyncProtocol.MAX_TRANSACTION_BYTES) {
                    throw new IllegalArgumentException("Cached config sync exceeds transaction byte limit");
                }

                matching.put(entry.getKey(), payload);
            }
        }

        if (!matching.isEmpty()) {
            ClientSyncResult result = TRANSACTIONS.receiveCached(matching);

            if (result.disconnectReason() != null) {
                disconnect(result.disconnectReason());
                return;
            }
        }

        request(requested);
    }

    private static void rejectHandshake(RuntimeException failure) {
        SERVER_CONFIG_HASHES.clear();
        TRANSACTIONS.clear();
        LiteConfigCommon.error("Failed to process config sync manifest", failure);
        disconnect(TranslationKey.SYNC_FAILED);
    }

    private static void request(List<String> configIds) {
        for (int start = 0; start < configIds.size(); start += ENTRIES_PER_PACKET) {
            int end = Math.min(configIds.size(), start + ENTRIES_PER_PACKET);

            CommonClientPlatform platform = PLATFORM;
            if (platform != null) {
                platform.request(new ConfigSyncRequestC2SPacket(
                    new LinkedHashSet<>(configIds.subList(start, end))));
            }
        }
    }

    private static void disconnect(TranslationKey reason) {
        CommonClientPlatform platform = PLATFORM;
        if (platform != null) {
            platform.disconnect(reason);
        }
    }
}
