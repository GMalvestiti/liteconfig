package com.gmalvestiti.minecraft.liteconfig.network;

import com.gmalvestiti.minecraft.liteconfig.api.EntryNormalization;
import com.gmalvestiti.minecraft.liteconfig.api.SyncMode;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Config;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.AllowedValues;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Pattern;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Entry;
import com.gmalvestiti.minecraft.liteconfig.api.metadata.ConfigProperty;
import com.gmalvestiti.minecraft.liteconfig.context.ConfigSettings;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigScope;
import com.gmalvestiti.minecraft.liteconfig.network.packet.ConfigSyncS2CPacket;
import com.gmalvestiti.minecraft.liteconfig.registry.RegisteredConfig;
import com.gmalvestiti.minecraft.liteconfig.support.ConfigRegistryIsolation;
import com.gmalvestiti.minecraft.liteconfig.support.RegisteredConfigs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(ConfigRegistryIsolation.class)
class SyncExclusionTest {

    @Test
    void excludesLeavesAndEntireSubtreesFromInheritedSync(@TempDir Path directory) {
        var config = create(InheritedSync.class, directory);
        var metadata = config.model().metadata();

        assertEquals(List.of("rules.limit", "shared"),
            metadata.synced().stream().map(ConfigProperty::path).toList());
        assertFalse(metadata.property("local").orElseThrow().sync());
        assertFalse(metadata.property("privateSection").orElseThrow().sync());
        assertFalse(metadata.property("privateSection.limit").orElseThrow().sync());
        assertFalse(metadata.property("rules.local").orElseThrow().sync());
    }

    @Test
    void exclusionTakesPrecedenceOverExplicitSync(@TempDir Path directory) {
        var config = create(SelectedSync.class, directory);
        assertEquals(List.of("rules.limit"),
            config.model().metadata().synced().stream().map(ConfigProperty::path).toList());
    }

    @Test
    void excludedValuesNeverAffectPayloadOrBroadcastHash(@TempDir Path directory) {
        var config = create(InheritedSync.class, directory);
        var synced = SyncedConfig.of(config);
        var before = synced.cachedSnapshot();
        var candidate = config.state().copyOfCanonical();
        candidate.local = "server secret";
        candidate.privateSection.limit = 999;
        candidate.rules.local = "another secret";
        config.state().replace(candidate);

        assertEquals(before.data(), synced.cachedSnapshot().data());
        assertEquals(before.hash(), synced.cachedSnapshot().hash());
        assertNull(synced.changedSnapshot());
    }

    @Test
    void remoteOverlayPreservesClientValuesIncludingExcludedRestartFields(@TempDir Path directory) {
        var source = create(InheritedSync.class, directory.resolve("server"));
        var target = create(InheritedSync.class, directory.resolve("client"));
        var server = source.state().copyOfCanonical();
        server.shared = 12;
        server.rules.limit = 27;
        server.local = "server secret";
        server.privateSection.limit = 999;
        server.rules.local = "server nested secret";
        source.state().replace(server);
        var local = target.state().copyOfCanonical();
        local.local = "client preference";
        local.privateSection.limit = 31;
        local.rules.local = "client nested preference";
        target.state().replace(local);

        var sender = SyncedConfig.of(source);
        var receiver = SyncedConfig.of(target);
        var transactions = new ConfigSyncTransactions(id -> id.equals(receiver.id()) ? receiver : null);
        var result = transactions.receive(
            new ConfigSyncS2CPacket(0, true, true, Map.of(sender.id(), sender.cachedSnapshot().data())));

        assertTrue(result.completed());
        assertFalse(result.restartRequired());
        assertNull(result.disconnectReason());
        assertEquals(12, target.state().published().shared);
        assertEquals(27, target.state().published().rules.limit);
        assertEquals("client preference", target.state().published().local);
        assertEquals(31, target.state().published().privateSection.limit);
        assertEquals("client nested preference", target.state().published().rules.local);
        receiver.clearRemote();
        assertEquals(1, target.state().published().shared);
        assertEquals("client preference", target.state().published().local);
    }

    @Test
    void changingOneLeafStillSendsOtherSelectedValues(@TempDir Path directory) {
        var source = create(InheritedSync.class, directory.resolve("server"));
        var first = source.state().copyOfCanonical();
        first.rules.limit = 27;
        source.state().replace(first);
        var sender = SyncedConfig.of(source);
        sender.cachedSnapshot();
        var next = source.state().copyOfCanonical();
        next.shared = 12;
        source.state().replace(next);

        var changed = sender.changedSnapshot();
        assertTrue(changed != null);
        var target = create(InheritedSync.class, directory.resolve("client"));
        var receiver = SyncedConfig.of(target);
        var result = new ConfigSyncTransactions(id -> id.equals(receiver.id()) ? receiver : null)
            .receive(new ConfigSyncS2CPacket(0, true, true, Map.of(sender.id(), changed.data())));

        assertTrue(result.completed());
        assertEquals(12, target.state().published().shared);
        assertEquals(27, target.state().published().rules.limit);
        assertNull(sender.changedSnapshot());
    }

    @Test
    void normalizesRemoteValuesBeforeValidationAndRestartSelection(@TempDir Path directory) {
        var source = create(NormalizedSync.class, directory.resolve("server"));
        var target = create(NormalizedSync.class, directory.resolve("client"));
        var initial = target.state().published();
        var raw = source.state().copyOfCanonical();
        raw.database = " MYSQL ";
        raw.mode = "\t safe ";
        raw.preserved = " cUsToM ";
        source.state().replace(raw);
        var sender = SyncedConfig.of(source);
        var receiver = SyncedConfig.of(target);
        var transactions = new ConfigSyncTransactions(id -> id.equals(receiver.id()) ? receiver : null);

        var result = transactions.receive(
            new ConfigSyncS2CPacket(0, true, true, Map.of(sender.id(), sender.cachedSnapshot().data())));

        assertTrue(result.completed());
        assertFalse(result.restartRequired());
        assertEquals("mysql", target.state().published().database);
        assertEquals("SAFE", target.state().published().mode);
        assertEquals("cUsToM", target.state().published().preserved);
        assertEquals("sqlite", initial.database);
        raw = source.state().copyOfCanonical();
        raw.mode = " fast ";
        source.state().replace(raw);
        result = transactions.receive(
            new ConfigSyncS2CPacket(0, true, false, Map.of(sender.id(), sender.cachedSnapshot().data())));

        assertTrue(result.completed());
        assertTrue(result.restartRequired());
        assertEquals("SAFE", target.state().published().mode);
        assertEquals("FAST", target.state().pending().orElseThrow().mode);
        receiver.clearRemote();
        assertEquals("sqlite", target.state().published().database);
        assertEquals("SAFE", target.state().published().mode);
    }

    private static <T> RegisteredConfig<T> create(Class<T> type, Path directory) {
        return RegisteredConfigs.create(new ConfigSettings<>(
            type, new ConfigScope("sync-exclusion"), directory.toAbsolutePath().normalize()));
    }

    @Config(name = "inherited-exclusion", sync = SyncMode.ENABLED)
    public static class InheritedSync {
        @Entry(sync = SyncMode.INHERIT)
        public int shared = 1;
        @Entry(sync = SyncMode.DISABLED, restart = true)
        public String local = "default";
        @Entry(sync = SyncMode.DISABLED)
        public Rules privateSection = new Rules();
        @Entry(comment = "Inherits synchronization without a sync argument.")
        public Rules rules = new Rules();
    }

    @Config(name = "selected-exclusion")
    public static class SelectedSync {
        @Entry(sync = SyncMode.DISABLED)
        public int local = 1;
        @Entry(sync = SyncMode.DISABLED)
        public Rules privateSection = new Rules();
        @Entry(sync = SyncMode.ENABLED)
        public Rules rules = new Rules();
    }

    public static class Rules {
        @Entry(sync = SyncMode.ENABLED)
        public int limit = 4;
        @Entry(sync = SyncMode.DISABLED)
        public String local = "nested default";
    }

    @Config(name = "normalized-sync", sync = SyncMode.ENABLED)
    public static class NormalizedSync {
        @AllowedValues({"MySQL", "SQLite"})
        @Pattern("[a-z]+")
        public String database = "SQLite";
        @Entry(restart = true)
        @AllowedValues(value = {"Fast", "Safe"}, normalize = EntryNormalization.UPPER_CASE)
        @Pattern("[A-Z]+")
        public String mode = "Safe";
        @AllowedValues(value = "Custom", normalize = EntryNormalization.NONE)
        public String preserved = "CuStOm";
    }
}
