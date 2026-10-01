package com.gmalvestiti.minecraft.liteconfig.network;

import com.gmalvestiti.minecraft.liteconfig.network.packet.ConfigSyncS2CPacket;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ConfigSyncTransactionsTest {

    private final ConfigSyncTransactions transactions =
        new ConfigSyncTransactions(Map.<String, SyncedConfig<?>>of()::get);

    @Test
    void singlePacketTransactionsDoNotOccupyPendingSlots() {
        for (int id = 0; id <= ConfigSyncProtocol.MAX_PENDING_TRANSACTIONS; id++) {
            ClientSyncResult result = transactions.receive(packet(id, true, Map.of()));
            assertTrue(result.completed());
            assertNull(result.disconnectReason());
        }
    }

    @Test
    void singlePacketTransactionsRespectPendingSlotLimit() {
        for (int id = 0; id < ConfigSyncProtocol.MAX_PENDING_TRANSACTIONS; id++) {
            assertFalse(transactions.receive(packet(id, false, Map.of())).completed());
        }

        assertFailed(transactions.receive(
            packet(ConfigSyncProtocol.MAX_PENDING_TRANSACTIONS, true, Map.of())));
        assertNull(transactions.receive(packet(100, true, Map.of())).disconnectReason());
    }

    @Test
    void duplicateEntriesRejectAndClearAllPendingTransactions() {
        ConfigBytes bytes = ConfigBytes.of(new byte[] {1});
        assertFalse(transactions.receive(packet(1, false, Map.of("config", bytes))).completed());
        assertFalse(transactions.receive(packet(2, false, Map.of("other", bytes))).completed());

        assertFailed(transactions.receive(packet(1, true, Map.of("config", bytes))));
        assertNull(transactions.receive(packet(2, true, Map.of("other", bytes))).disconnectReason());
    }

    @Test
    void completingTransactionsReleasesPendingSlots() {
        for (int id = 0; id < ConfigSyncProtocol.MAX_PENDING_TRANSACTIONS; id++) {
            assertFalse(transactions.receive(packet(id, false, Map.of())).completed());
        }

        assertNull(transactions.receive(packet(0, true, Map.of())).disconnectReason());
        assertFalse(transactions.receive(packet(100, false, Map.of())).completed());
    }

    @Test
    void singlePacketTransactionsRespectAggregateByteLimit() {
        ConfigBytes bytes = ConfigBytes.of(new byte[ConfigSyncProtocol.MAX_CONFIG_BYTES]);
        int packets = ConfigSyncProtocol.MAX_TRANSACTION_BYTES / bytes.size();
        for (int index = 0; index < packets; index++) {
            assertFalse(transactions.receive(
                packet(1, false, Map.of("config." + index, bytes))).completed());
        }

        assertFailed(transactions.receive(packet(2, true, Map.of("overflow", bytes))));
        assertNull(transactions.receive(packet(2, true, Map.of("overflow", bytes))).disconnectReason());
    }

    @Test
    void singlePacketTransactionsRespectAggregateEntryLimit() {
        ConfigBytes bytes = ConfigBytes.of(new byte[0]);
        int packets = ConfigSyncProtocol.MAX_TRANSACTION_ENTRIES / ConfigSyncProtocol.ENTRIES_PER_PACKET;
        for (int index = 0; index < packets; index++) {
            Map<String, ConfigBytes> entries = new LinkedHashMap<>();
            for (int entry = 0; entry < ConfigSyncProtocol.ENTRIES_PER_PACKET; entry++) {
                entries.put("config." + index + "." + entry, bytes);
            }
            assertFalse(transactions.receive(packet(index % 2, false, entries)).completed());
        }

        assertFailed(transactions.receive(packet(2, true, Map.of("overflow", bytes))));
        assertNull(transactions.receive(packet(2, true, Map.of("overflow", bytes))).disconnectReason());
    }

    @Test
    void completingTransactionsReleasesPendingBytesAndEntries() {
        ConfigBytes bytes = ConfigBytes.of(new byte[ConfigSyncProtocol.MAX_CONFIG_BYTES]);
        int packets = ConfigSyncProtocol.MAX_TRANSACTION_BYTES / bytes.size();
        for (int index = 0; index < packets; index++) {
            assertFalse(transactions.receive(
                packet(1, false, Map.of("config." + index, bytes))).completed());
        }

        assertNull(transactions.receive(packet(1, true, Map.of())).disconnectReason());
        assertNull(transactions.receive(packet(2, true, Map.of("next", bytes))).disconnectReason());
    }

    @Test
    void clearingTransactionsResetsPacketAndResourceAccounting() {
        assertFalse(transactions.receive(
            packet(1, false, Map.of("config", ConfigBytes.of(new byte[0])))).completed());
        transactions.clear();

        assertNull(transactions.receive(
            packet(1, true, Map.of("config", ConfigBytes.of(new byte[0])))).disconnectReason());
    }

    private static ConfigSyncS2CPacket packet(long id, boolean last, Map<String, ConfigBytes> entries) {
        return new ConfigSyncS2CPacket(id, last, entries);
    }

    private static void assertFailed(ClientSyncResult result) {
        assertTrue(result.completed());
        assertFalse(result.restartRequired());
        assertEquals(ClientConfigSync.SYNC_FAILED, result.disconnectReason());
    }
}
