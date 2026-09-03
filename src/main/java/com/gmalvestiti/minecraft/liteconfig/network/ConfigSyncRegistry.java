package com.gmalvestiti.minecraft.liteconfig.network;

import com.gmalvestiti.minecraft.liteconfig.LiteConfigCommon;
import com.gmalvestiti.minecraft.liteconfig.async.ConfigExecutors;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigError;
import com.gmalvestiti.minecraft.liteconfig.exception.LiteConfigException;
import com.gmalvestiti.minecraft.liteconfig.network.packet.ConfigSyncHandshakeS2CPacket;
import com.gmalvestiti.minecraft.liteconfig.network.packet.ConfigSyncRequestC2SPacket;
import com.gmalvestiti.minecraft.liteconfig.network.packet.ConfigSyncS2CPacket;
import com.gmalvestiti.minecraft.liteconfig.registry.ConfigRegistry;
import com.gmalvestiti.minecraft.liteconfig.registry.RegisteredConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static com.gmalvestiti.minecraft.liteconfig.network.ConfigSyncProtocol.ENTRIES_PER_PACKET;

public final class ConfigSyncRegistry {

    public static final String RESTART_REQUIRED = "liteconfig.disconnect.restart_required";
    public static final String SYNC_FAILED = "liteconfig.disconnect.sync_failed";

    private static final Map<String, SyncedConfig<?>> SYNCED = new ConcurrentHashMap<>();

    private static final Consumer<List<ConfigSyncS2CPacket>> NO_BROADCAST = ignored -> {};
    private static final Consumer<ConfigSyncRequestC2SPacket> NO_REQUEST = ignored -> {};
    private static final Consumer<String> NO_DISCONNECT = ignored -> {};
    private static final Runnable NO_MANIFEST = () -> {};

    private static final Map<String, ConfigBytes> SERVER_HASHES = new ConcurrentHashMap<>();
    private static final Map<Long, PendingTransaction> PENDING_TRANSACTIONS = new LinkedHashMap<>();
    private static final AtomicLong NEXT_TRANSACTION_ID = new AtomicLong();
    private static final AtomicLong CONNECTION_GENERATION = new AtomicLong();
    private static final Object CLIENT_CONNECTION_LOCK = new Object();

    private static volatile Consumer<List<ConfigSyncS2CPacket>> broadcastScheduler = NO_BROADCAST;
    private static volatile Consumer<ConfigSyncRequestC2SPacket> requestScheduler = NO_REQUEST;
    private static volatile Consumer<String> disconnectScheduler = NO_DISCONNECT;
    private static volatile Runnable manifestScheduler = NO_MANIFEST;
    private static volatile Executor clientMainThreadExecutor = Runnable::run;
    private static volatile BooleanSupplier remoteConnectionCheck = () -> false;

    private static int totalPendingBytes;
    private static int totalPendingEntries;
    private static boolean initialized;

    private ConfigSyncRegistry() {}

    public static synchronized void initialize() {
        if (initialized) {
            return;
        }

        ConfigRegistry.setLifecycleCallbacks(ConfigSyncRegistry::register, ConfigSyncRegistry::unregister);
        initialized = true;
    }

    public static synchronized void register(RegisteredConfig<?> config) {
        if (config.model().metadata().synced().isEmpty()) {
            return;
        }

        SyncedConfig<?> synced = SyncedConfig.of(config);
        SyncedConfig<?> existing = SYNCED.putIfAbsent(synced.id(), synced);

        if (existing != null) {
            if (!existing.wraps(config)) {
                throw synced.scope().exception(ConfigError.CONFLICTING_SYNC_ROOT, synced.id());
            }
            return;
        }

        try {
            manifestScheduler.run();
            requestIfChanged(synced);
            synced.addBroadcastListener(() -> broadcast(synced));
        } catch (RuntimeException | Error failure) {
            SYNCED.remove(synced.id(), synced);
            LiteConfigCommon.error("Failed to register config sync", failure);
            throw failure;
        }
    }

    public static synchronized void unregister(RegisteredConfig<?> config) {
        SyncedConfig<?> synced = SYNCED.get(config.model().syncId());
        if (synced != null && synced.wraps(config) && SYNCED.remove(synced.id(), synced)) {
            synced.close();
            manifestScheduler.run();
        }
    }

    public static void setBroadcastScheduler(Consumer<List<ConfigSyncS2CPacket>> scheduler) {
        broadcastScheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    public static void setRequestScheduler(Consumer<ConfigSyncRequestC2SPacket> scheduler) {
        requestScheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    public static void setDisconnectScheduler(Consumer<String> scheduler) {
        disconnectScheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    public static void setManifestScheduler(Runnable scheduler) {
        manifestScheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    public static void setClientMainThreadExecutor(Executor executor) {
        clientMainThreadExecutor = Objects.requireNonNull(executor, "executor");
    }

    public static void setRemoteConnectionCheck(BooleanSupplier check) {
        remoteConnectionCheck = Objects.requireNonNull(check, "check");
    }

    public static boolean hasRemoteConnection() {
        return remoteConnectionCheck.getAsBoolean();
    }

    static void runOnClientMainThread(Runnable task) {
        clientMainThreadExecutor.execute(task);
    }

    public static List<ConfigSyncHandshakeS2CPacket> beginHandshake() {
        if (SYNCED.isEmpty()) {
            return List.of();
        }

        Map<String, ConfigBytes> hashes = new TreeMap<>();

        SYNCED.values().forEach(synced -> hashes.put(synced.id(), synced.cachedSnapshot().hash()));
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

    public static void receiveHandshake(ConfigSyncHandshakeS2CPacket handshake) {
        List<String> requested = new ArrayList<>();

        long generation;
        synchronized (CLIENT_CONNECTION_LOCK) {
            generation = CONNECTION_GENERATION.get();

            for (Map.Entry<String, ConfigBytes> entry : handshake.hashes().entrySet()) {
                if (!SERVER_HASHES.containsKey(entry.getKey())
                    && SERVER_HASHES.size() == ConfigSyncProtocol.MAX_MANIFEST_ENTRIES) {

                    SERVER_HASHES.clear();
                    disconnect(generation, SYNC_FAILED);
                    return;
                }

                SERVER_HASHES.put(entry.getKey(), entry.getValue());
                SyncedConfig<?> synced = SYNCED.get(entry.getKey());

                if (synced != null && synced.differsFrom(entry.getValue())) {
                    requested.add(entry.getKey());
                }
            }
        }

        request(requested);
    }

    public static List<ConfigSyncS2CPacket> payloadsFor(ConfigSyncRequestC2SPacket request) {
        if (SYNCED.isEmpty() || request.configIds().isEmpty()) {
            return List.of();
        }

        Map<String, ConfigBytes> configs = new TreeMap<>();

        request.configIds().forEach(configId -> {
            SyncedConfig<?> synced = SYNCED.get(configId);

            if (synced != null) {
                configs.put(configId, synced.cachedSnapshot().data());
            }
        });

        return packets(configs);
    }

    public static CompletableFuture<ReceiveResult> receiveResultAsync(
        ConfigSyncS2CPacket payload
    ) {
        long generation = CONNECTION_GENERATION.get();
        return CompletableFuture.supplyAsync(
            () -> receivePacket(payload, generation), ConfigExecutors.defaultExecutor());
    }

    static boolean receive(Map<String, ConfigBytes> configs) {
        ReceiveResult result = applyTransaction(configs, CONNECTION_GENERATION.get());
        return result.completed() && result.restartRequired();
    }

    static boolean apply(String configId, ConfigBytes payload) {
        SyncedConfig<?> synced = SYNCED.get(configId);

        if (synced == null) {
            return false;
        }

        try {
            return synced.apply(payload);
        } catch (LiteConfigException failure) {
            synced.scope().logError(failure.rawMessage(), failure);
            return false;
        }
    }

    public static void resetClientConnection() {
        synchronized (CLIENT_CONNECTION_LOCK) {
            CONNECTION_GENERATION.incrementAndGet();
            SERVER_HASHES.clear();
        }
        ConfigExecutors.defaultExecutor().execute(ConfigSyncRegistry::clearPending);
    }

    public static void handleClientResult(ReceiveResult result) {
        if (result.disconnectReason() != null) {
            disconnect(result.connectionGeneration(), result.disconnectReason());
        }
    }

    private static void broadcast(SyncedConfig<?> synced) {
        SyncedConfig.Snapshot snapshot = synced.changedSnapshot();
        if (snapshot != null) {
            broadcastScheduler.accept(packets(Map.of(synced.id(), snapshot.data())));
        }
    }

    private static void requestIfChanged(SyncedConfig<?> synced) {
        ConfigBytes serverHash = SERVER_HASHES.get(synced.id());
        if (serverHash != null && synced.differsFrom(serverHash)) {
            request(List.of(synced.id()));
        }
    }

    private static void request(List<String> configIds) {
        for (int start = 0; start < configIds.size(); start += ENTRIES_PER_PACKET) {
            int end = Math.min(configIds.size(), start + ENTRIES_PER_PACKET);

            requestScheduler.accept(new ConfigSyncRequestC2SPacket(
                new LinkedHashSet<>(configIds.subList(start, end))));
        }
    }

    private static List<ConfigSyncS2CPacket> packets(Map<String, ConfigBytes> configs) {
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
                transactionId,
                index == batches.size() - 1, batches.get(index)));
        }

        return packets;
    }

    private static ReceiveResult receivePacket(
        ConfigSyncS2CPacket payload,
        long connectionGeneration
    ) {
        try {
            if (connectionChanged(connectionGeneration)) {
                return ReceiveResult.ignored(connectionGeneration);
            }

            PendingTransaction pending = PENDING_TRANSACTIONS.get(payload.transactionId());

            if (pending == null) {
                if (PENDING_TRANSACTIONS.size() >= ConfigSyncProtocol.MAX_PENDING_TRANSACTIONS) {
                    clearPending();
                    return ReceiveResult.failed(connectionGeneration);
                }

                pending = new PendingTransaction();
                PENDING_TRANSACTIONS.put(payload.transactionId(), pending);
            }

            pending.packetCount++;
            if (pending.packetCount > ConfigSyncProtocol.MAX_TRANSACTION_PACKETS) {
                clearPending();
                return ReceiveResult.failed(connectionGeneration);
            }

            for (Map.Entry<String, ConfigBytes> entry : payload.configs().entrySet()) {
                if (pending.configs.size() >= ConfigSyncProtocol.MAX_TRANSACTION_ENTRIES
                    || totalPendingEntries >= ConfigSyncProtocol.MAX_TRANSACTION_ENTRIES
                    || pending.bytes + entry.getValue().size() > ConfigSyncProtocol.MAX_TRANSACTION_BYTES
                    || totalPendingBytes + entry.getValue().size() > ConfigSyncProtocol.MAX_TRANSACTION_BYTES) {

                    clearPending();
                    return ReceiveResult.failed(connectionGeneration);
                }

                if (pending.configs.putIfAbsent(entry.getKey(), entry.getValue()) != null) {
                    clearPending();
                    return ReceiveResult.failed(connectionGeneration);
                }

                pending.bytes += entry.getValue().size();
                totalPendingBytes += entry.getValue().size();
                totalPendingEntries++;
            }

            if (!payload.last()) {
                return ReceiveResult.pending(connectionGeneration);
            }

            PENDING_TRANSACTIONS.remove(payload.transactionId());
            totalPendingBytes -= pending.bytes;
            totalPendingEntries -= pending.configs.size();

            Map<String, ConfigBytes> transaction =
                Collections.unmodifiableMap(new LinkedHashMap<>(pending.configs));

            return applyTransaction(transaction, connectionGeneration);
        } catch (RuntimeException failure) {
            clearPending();
            LiteConfigCommon.error("Failed to receive config sync transaction", failure);
            return ReceiveResult.failed(connectionGeneration);
        }
    }

    private static ReceiveResult applyTransaction(
        Map<String, ConfigBytes> configs,
        long connectionGeneration
    ) {
        List<SyncedConfig.Prepared<?>> prepared = new ArrayList<>(configs.size());

        try {
            if (connectionChanged(connectionGeneration)) {
                return ReceiveResult.ignored(connectionGeneration);
            }

            for (Map.Entry<String, ConfigBytes> config : configs.entrySet()) {
                SyncedConfig<?> synced = SYNCED.get(config.getKey());

                if (synced != null) {
                    prepared.add(synced.prepare(config.getValue()));
                }
            }
        } catch (LiteConfigException failure) {
            LiteConfigCommon.info(String.format("Rejected config sync transaction: %s", failure.rawMessage()));
            return ReceiveResult.failed(connectionGeneration);
        } catch (RuntimeException failure) {
            LiteConfigCommon.error("Failed to prepare config sync transaction", failure);
            return ReceiveResult.failed(connectionGeneration);
        }

        boolean restartRequired = false;
        List<SyncedConfig.Prepared<?>> committed = new ArrayList<>(prepared.size());
        try {
            for (SyncedConfig.Prepared<?> change : prepared) {
                if (connectionChanged(connectionGeneration)) {
                    rollback(committed);
                    return ReceiveResult.ignored(connectionGeneration);
                }

                if (!change.commitDeferred()) {
                    rollback(committed);
                    return ReceiveResult.failed(connectionGeneration);
                }

                committed.add(change);
                restartRequired |= change.restartRequired();
            }
        } catch (RuntimeException failure) {
            rollback(committed);
            LiteConfigCommon.error("Failed to apply config sync transaction", failure);
            return ReceiveResult.failed(connectionGeneration);
        }

        try {
            committed.forEach(SyncedConfig.Prepared::dispatch);
        } catch (RuntimeException failure) {
            LiteConfigCommon.error("Failed to dispatch committed config sync transaction", failure);
            return ReceiveResult.failed(connectionGeneration);
        }

        return ReceiveResult.completed(connectionGeneration, restartRequired);
    }

    private static void rollback(List<SyncedConfig.Prepared<?>> committed) {
        for (int index = committed.size() - 1; index >= 0; index--) {
            if (!committed.get(index).rollback()) {
                LiteConfigCommon.error(
                    "Failed to roll back a partially applied config sync transaction",
                    new IllegalStateException("Config changed or could not be persisted during rollback"));
            }
        }
    }

    private static void clearPending() {
        PENDING_TRANSACTIONS.clear();
        totalPendingBytes = 0;
        totalPendingEntries = 0;
    }

    public record ReceiveResult(
        boolean completed,
        boolean restartRequired,
        String disconnectReason,
        long connectionGeneration
    ) {

        private static ReceiveResult pending(long connectionGeneration) {
            return new ReceiveResult(false, false, null, connectionGeneration);
        }

        private static ReceiveResult completed(
            long connectionGeneration,
            boolean restartRequired
        ) {
            return new ReceiveResult(
                true,
                restartRequired,
                restartRequired ? RESTART_REQUIRED : null,
                connectionGeneration);
        }

        private static ReceiveResult failed(long connectionGeneration) {
            return new ReceiveResult(true, false, SYNC_FAILED, connectionGeneration);
        }

        private static ReceiveResult ignored(long connectionGeneration) {
            return new ReceiveResult(true, false, null, connectionGeneration);
        }
    }

    private static final class PendingTransaction {

        private final Map<String, ConfigBytes> configs = new LinkedHashMap<>();
        private int packetCount;
        private int bytes;
    }

    private static boolean connectionChanged(long generation) {
        synchronized (CLIENT_CONNECTION_LOCK) {
            return CONNECTION_GENERATION.get() != generation;
        }
    }

    private static void disconnect(long generation, String reason) {
        runOnClientMainThread(() -> {
            synchronized (CLIENT_CONNECTION_LOCK) {
                if (CONNECTION_GENERATION.get() == generation) {
                    disconnectScheduler.accept(reason);
                }
            }
        });
    }
}
