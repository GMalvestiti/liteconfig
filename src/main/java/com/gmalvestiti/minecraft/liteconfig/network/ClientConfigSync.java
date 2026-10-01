package com.gmalvestiti.minecraft.liteconfig.network;

import com.gmalvestiti.minecraft.liteconfig.network.packet.ConfigSyncHandshakeS2CPacket;
import com.gmalvestiti.minecraft.liteconfig.network.packet.ConfigSyncRequestC2SPacket;
import com.gmalvestiti.minecraft.liteconfig.network.packet.ConfigSyncS2CPacket;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static com.gmalvestiti.minecraft.liteconfig.network.ConfigSyncProtocol.ENTRIES_PER_PACKET;

public final class ClientConfigSync {

    public static final String RESTART_REQUIRED = "liteconfig.disconnect.restart_required";
    public static final String SYNC_FAILED = "liteconfig.disconnect.sync_failed";

    private static final Map<String, ConfigBytes> SERVER_HASHES = new HashMap<>();
    private static final ConfigSyncTransactions TRANSACTIONS =
        new ConfigSyncTransactions(ConfigSyncRegistry::get);

    private static volatile Executor CLIENT_MAIN_THREAD_EXECUTOR;
    private static volatile BooleanSupplier REMOTE_CONNECTION_CHECK = () -> false;
    private static volatile Consumer<ConfigSyncRequestC2SPacket> REQUEST_SCHEDULER = ignored -> {};
    private static volatile Consumer<String> DISCONNECT_SCHEDULER = ignored -> {};

    private ClientConfigSync() {}

    public static void setClientMainThreadExecutor(Executor executor) {
        CLIENT_MAIN_THREAD_EXECUTOR = Objects.requireNonNull(executor, "executor");
        ConfigSyncRegistry.activate();
    }

    static Executor availableMainThreadExecutor() {
        return CLIENT_MAIN_THREAD_EXECUTOR;
    }

    static Executor clientMainThreadExecutor() {
        Executor executor = CLIENT_MAIN_THREAD_EXECUTOR;
        if (executor == null) {
            throw new RejectedExecutionException("The client main-thread executor is not available");
        }
        return executor;
    }

    public static void setRemoteConnectionCheck(BooleanSupplier check) {
        REMOTE_CONNECTION_CHECK = Objects.requireNonNull(check, "check");
    }

    public static boolean hasRemoteConnection() {
        return CLIENT_MAIN_THREAD_EXECUTOR != null && REMOTE_CONNECTION_CHECK.getAsBoolean();
    }

    public static void setRequestScheduler(Consumer<ConfigSyncRequestC2SPacket> scheduler) {
        REQUEST_SCHEDULER = Objects.requireNonNull(scheduler, "scheduler");
    }

    public static void setDisconnectScheduler(Consumer<String> scheduler) {
        DISCONNECT_SCHEDULER = Objects.requireNonNull(scheduler, "scheduler");
    }

    public static void receiveHandshake(ConfigSyncHandshakeS2CPacket handshake) {
        clientMainThreadExecutor().execute(() -> {
            if (handshake.hashes().isEmpty()) {
                return;
            }

            List<String> requested = new ArrayList<>();
            for (Map.Entry<String, ConfigBytes> entry : handshake.hashes().entrySet()) {

                if (!SERVER_HASHES.containsKey(entry.getKey())
                    && SERVER_HASHES.size() == ConfigSyncProtocol.MAX_MANIFEST_ENTRIES) {

                    SERVER_HASHES.clear();
                    DISCONNECT_SCHEDULER.accept(SYNC_FAILED);
                    return;
                }

                SERVER_HASHES.put(entry.getKey(), entry.getValue());
                SyncedConfig<?> synced = ConfigSyncRegistry.get(entry.getKey());

                if (synced != null && synced.differsFrom(entry.getValue())) {
                    requested.add(entry.getKey());
                }
            }

            request(requested);
        });
    }

    public static CompletableFuture<ClientSyncResult> receivePayload(ConfigSyncS2CPacket payload) {
        return CompletableFuture.supplyAsync(() -> {
            ClientSyncResult result = TRANSACTIONS.receive(payload);

            if (result.disconnectReason() != null) {
                DISCONNECT_SCHEDULER.accept(result.disconnectReason());
            }

            return result;
        }, clientMainThreadExecutor());
    }

    public static void resetClientConnection() {
        Runnable clear = () -> {
            SERVER_HASHES.clear();
            TRANSACTIONS.clear();
        };

        Executor executor = CLIENT_MAIN_THREAD_EXECUTOR;

        if (executor == null) {
            clear.run();
            return;
        }

        executor.execute(clear);
    }

    static void requestIfChanged(SyncedConfig<?> synced) {
        Executor executor = CLIENT_MAIN_THREAD_EXECUTOR;

        if (executor == null) {
            return;
        }

        executor.execute(() -> {
            if (ConfigSyncRegistry.get(synced.id()) != synced) {
                return;
            }

            ConfigBytes serverHash = SERVER_HASHES.get(synced.id());

            if (serverHash != null && synced.differsFrom(serverHash)) {
                request(List.of(synced.id()));
            }
        });
    }

    private static void request(List<String> configIds) {
        for (int start = 0; start < configIds.size(); start += ENTRIES_PER_PACKET) {

            int end = Math.min(configIds.size(), start + ENTRIES_PER_PACKET);

            REQUEST_SCHEDULER.accept(new ConfigSyncRequestC2SPacket(
                new LinkedHashSet<>(configIds.subList(start, end))));
        }
    }
}
