package com.gmalvestiti.minecraft.liteconfig.network;

import com.gmalvestiti.minecraft.liteconfig.LiteConfigCommon;
import com.gmalvestiti.minecraft.liteconfig.exception.LiteConfigException;
import com.gmalvestiti.minecraft.liteconfig.network.packet.ConfigSyncS2CPacket;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

final class ConfigSyncTransactions {

    private final Function<String, SyncedConfig<?>> synced;
    private final Map<Long, PendingTransaction> pending = new HashMap<>();
    private int pendingBytes;
    private int pendingEntries;

    ConfigSyncTransactions(Function<String, SyncedConfig<?>> synced) {
        this.synced = synced;
    }

    ClientSyncResult receive(ConfigSyncS2CPacket packet) {
        try {
            PendingTransaction transaction = pending.get(packet.transactionId());
            if (transaction == null && pending.size() >= ConfigSyncProtocol.MAX_PENDING_TRANSACTIONS) {
                throw new IllegalArgumentException("Too many pending config sync transactions");
            }

            int bytes = 0;
            for (ConfigBytes value : packet.configs().values()) {
                bytes += value.size();
            }

            if (pendingEntries + packet.configs().size() > ConfigSyncProtocol.MAX_TRANSACTION_ENTRIES
                || pendingBytes + bytes > ConfigSyncProtocol.MAX_TRANSACTION_BYTES) {
                throw new IllegalArgumentException("Config sync transaction exceeds pending entry or byte limits");
            }

            if (transaction == null && packet.last()) {
                return apply(packet.configs());
            }

            if (transaction == null) {
                transaction = new PendingTransaction();
                pending.put(packet.transactionId(), transaction);
            }

            if (++transaction.packetCount > ConfigSyncProtocol.MAX_TRANSACTION_PACKETS) {
                throw new IllegalArgumentException("Config sync transaction exceeds packet limit");
            }

            for (Map.Entry<String, ConfigBytes> entry : packet.configs().entrySet()) {
                if (transaction.configs.putIfAbsent(entry.getKey(), entry.getValue()) != null) {
                    throw new IllegalArgumentException("Duplicate config sync id " + entry.getKey());
                }
            }

            transaction.bytes += bytes;
            pendingBytes += bytes;
            pendingEntries += packet.configs().size();

            if (!packet.last()) {
                return ClientSyncResult.pending();
            }

            pending.remove(packet.transactionId());
            pendingBytes -= transaction.bytes;
            pendingEntries -= transaction.configs.size();

            return apply(transaction.configs);
        } catch (RuntimeException failure) {
            clear();
            LiteConfigCommon.error("Failed to receive config sync transaction", failure);
            return ClientSyncResult.failed();
        }
    }

    private ClientSyncResult apply(Map<String, ConfigBytes> configs) {
        List<SyncedConfig.Prepared<?>> prepared = new ArrayList<>(configs.size());

        try {
            for (Map.Entry<String, ConfigBytes> config : configs.entrySet()) {

                SyncedConfig<?> registration = synced.apply(config.getKey());

                if (registration != null) {
                    prepared.add(registration.prepare(config.getValue()));
                }
            }
        } catch (LiteConfigException failure) {
            LiteConfigCommon.info("Rejected config sync transaction: " + failure.rawMessage());
            return ClientSyncResult.failed();
        } catch (RuntimeException failure) {
            LiteConfigCommon.error("Failed to prepare config sync transaction", failure);
            return ClientSyncResult.failed();
        }

        boolean restartRequired = false;
        int committed = 0;

        try {
            for (SyncedConfig.Prepared<?> change : prepared) {

                if (!change.commitDeferred()) {
                    rollback(prepared, committed);
                    return ClientSyncResult.failed();
                }

                committed++;
                restartRequired |= change.restartRequired();
            }
        } catch (RuntimeException failure) {
            rollback(prepared, committed);
            LiteConfigCommon.error("Failed to apply config sync transaction", failure);
            return ClientSyncResult.failed();
        }

        try {
            prepared.forEach(SyncedConfig.Prepared::dispatch);
        } catch (RuntimeException failure) {
            LiteConfigCommon.error("Failed to dispatch committed config sync transaction", failure);
            return ClientSyncResult.failed();
        }

        return ClientSyncResult.completed(restartRequired);
    }

    void clear() {
        pending.clear();
        pendingBytes = 0;
        pendingEntries = 0;
    }

    private static void rollback(List<SyncedConfig.Prepared<?>> prepared, int committed) {
        for (int index = committed - 1; index >= 0; index--) {
            if (!prepared.get(index).rollback()) {
                LiteConfigCommon.error(
                    "Failed to roll back a partially applied config sync transaction",
                    new IllegalStateException("Config changed or could not be persisted during rollback"));
            }
        }
    }

    private static final class PendingTransaction {

        private final Map<String, ConfigBytes> configs = new LinkedHashMap<>();
        private int packetCount;
        private int bytes;
    }
}
