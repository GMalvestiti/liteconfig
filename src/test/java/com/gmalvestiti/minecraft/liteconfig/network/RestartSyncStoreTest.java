package com.gmalvestiti.minecraft.liteconfig.network;

import com.google.common.hash.Hashing;
import com.gmalvestiti.minecraft.liteconfig.api.ConfigExtension;
import com.gmalvestiti.minecraft.liteconfig.api.ConfigHolder;
import com.gmalvestiti.minecraft.liteconfig.api.ConfigType;
import com.gmalvestiti.minecraft.liteconfig.api.LiteConfig;
import com.gmalvestiti.minecraft.liteconfig.api.RestartSyncPolicy;
import com.gmalvestiti.minecraft.liteconfig.api.SyncMode;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Config;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Entry;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.AllowedValues;
import com.gmalvestiti.minecraft.liteconfig.api.spi.StateCloner;
import com.gmalvestiti.minecraft.liteconfig.api.spi.Violation;
import com.gmalvestiti.minecraft.liteconfig.context.ConfigSettings;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigScope;
import com.gmalvestiti.minecraft.liteconfig.exception.LiteConfigException;
import com.gmalvestiti.minecraft.liteconfig.network.packet.ConfigSyncS2CPacket;
import com.gmalvestiti.minecraft.liteconfig.registry.RegisteredConfig;
import com.gmalvestiti.minecraft.liteconfig.registry.ConfigRegistry;
import com.gmalvestiti.minecraft.liteconfig.shared.TranslationKey;
import com.gmalvestiti.minecraft.liteconfig.support.ConfigRegistryIsolation;
import com.gmalvestiti.minecraft.liteconfig.support.RegisteredConfigs;
import com.gmalvestiti.minecraft.liteconfig.support.TestSyncPlatform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(ConfigRegistryIsolation.class)
class RestartSyncStoreTest {

    @Test
    void cacheUsesConfigAndServerIdentityUnderConfiguredServersDirectory(@TempDir Path directory)
        throws Exception {
        ConfigBytes bytes = ConfigBytes.of(new byte[] {1, 2, 3});
        store(directory, "server-a").write(bytes);
        assertEquals(bytes, ConfigBytes.of(Files.readAllBytes(cachePath(directory, "mod:Config", "server-a"))));
        assertEquals(bytes, store(directory, "server-a").read());
        assertNull(store(directory, "server-b").read());
        assertNull(new RestartSyncStore(directory, new ConfigScope("mod"), "mod:Other", "server-a").read());
        assertFalse(Files.exists(directory.resolve(".liteconfig")));
    }

    @Test
    void cachedPayloadHasNoProcessAgeOrRestartEligibility(@TempDir Path directory) {
        ConfigBytes first = ConfigBytes.of(new byte[] {1});
        ConfigBytes second = ConfigBytes.of(new byte[] {2});
        RestartSyncStore cache = store(directory, "server-a");
        cache.write(first);
        assertEquals(first, store(directory, "server-a").read());
        cache.write(second);
        assertEquals(second, store(directory, "server-a").read());
    }

    @Test
    void oversizedCacheAndWritesAreRejected(@TempDir Path directory) throws Exception {
        RestartSyncStore cache = store(directory, "server-a");
        ConfigBytes previous = ConfigBytes.of(new byte[] {1});
        cache.write(previous);
        ConfigBytes oversized = ConfigBytes.of(new byte[ConfigSyncProtocol.MAX_CONFIG_BYTES + 1]);
        assertThrows(LiteConfigException.class, () -> cache.write(oversized));
        assertEquals(previous, cache.read());
        Files.write(cachePath(directory, "mod:Config", "server-a"), oversized.bytes());
        assertThrows(LiteConfigException.class, cache::read);
    }

    @Test
    void sharedCommitMarkerSelectsAllParticipants(@TempDir Path directory) {
        RestartSyncStore first = store(directory, "server-a");
        RestartSyncStore second = store(directory, "server-b");
        ConfigBytes old = ConfigBytes.of(new byte[] {1});
        ConfigBytes next = ConfigBytes.of(new byte[] {2});
        first.write(old);
        RestartSyncStore.Transaction transaction = new RestartSyncStore.Transaction();
        transaction.stage(first, next);
        transaction.stage(second, next);
        assertEquals(old, store(directory, "server-a").read());
        assertNull(store(directory, "server-b").read());
        transaction.commit();
        assertEquals(next, store(directory, "server-a").read());
        assertEquals(next, store(directory, "server-b").read());
    }

    @Test
    void failedCommitMarkerLeavesNormalFileAndCachesUnchanged(@TempDir Path directory) throws Exception {
        RestartSyncStore first = store(directory, "server-a");
        ConfigBytes old = ConfigBytes.of(new byte[] {1});
        first.write(old);
        Path normal = directory.resolve("config.json5");
        Files.writeString(normal, "old");
        RestartSyncStore file = RestartSyncStore.forFile(directory, normal, new ConfigScope("mod"));
        RestartSyncStore.Transaction transaction = new RestartSyncStore.Transaction();
        transaction.stage(first, ConfigBytes.of(new byte[] {2}));
        transaction.stageFile(file, "new".getBytes(StandardCharsets.UTF_8));
        Files.writeString(servers(directory).resolve(".transactions"), "obstruct marker");
        assertThrows(LiteConfigException.class, transaction::commit);
        assertEquals(old, store(directory, "server-a").read());
        assertEquals("old", new String(file.readFile(), StandardCharsets.UTF_8));
        assertEquals("old", Files.readString(normal));
    }

    @Test
    void committedJournalRecoversNormalFileAfterInterruptedMaterialization(@TempDir Path directory)
        throws Exception {
        Path normal = directory.resolve("config.json5");
        Files.writeString(normal, "old");
        RestartSyncStore file = RestartSyncStore.forFile(directory, normal, new ConfigScope("mod"));
        RestartSyncStore.Transaction transaction = new RestartSyncStore.Transaction();
        transaction.stage(store(directory, "server-a"), ConfigBytes.of(new byte[] {2}));
        transaction.stageFile(file, "new".getBytes(StandardCharsets.UTF_8));
        Files.delete(normal);
        Files.createDirectory(normal);
        Files.writeString(normal.resolve("obstruction"), "blocks replacement");
        transaction.commit();
        Files.delete(normal.resolve("obstruction"));
        Files.delete(normal);
        RestartSyncStore recovered = RestartSyncStore.forFile(directory, normal, new ConfigScope("mod"));
        assertEquals("new", new String(recovered.readFile(), StandardCharsets.UTF_8));
        assertEquals("new", Files.readString(normal));
        Files.writeString(normal, "manually edited");
        assertEquals("manually edited", new String(recovered.readFile(), StandardCharsets.UTF_8));
    }

    @Test
    void serverHolderStartupLoadsCommittedRestartJournalBeforeAnyConnection(@TempDir Path directory)
        throws Exception {
        RegisteredConfig<StrictConfig> original = create(StrictConfig.class, directory);
        Path normal = original.pathResolver().activePath().orElseThrow();
        leaveCommittedRestartJournal(directory, normal,
            "{ restart: 8, immediate: 'local', local: 'preference' }");
        assertTrue(Files.readString(normal).contains("\"restart\": 5"));
        ConfigRegistryIsolation.beforeGameStartup();
        try (ConfigHolder<StrictConfig> started = LiteConfig.holder(StrictConfig.class)
            .modId("mod").baseDir(directory).create()) {
            assertEquals(8, started.data().restart);
            assertEquals("local", started.data().immediate);
            assertTrue(started.pending().isEmpty());
            assertTrue(Files.readString(normal).contains("\"restart\": 8"));
        }
    }

    @Test
    void worldHolderStartupIgnoresLegacyFallbackFromAnotherWorld(@TempDir Path directory)
        throws Exception {
        WorldConfig.afterLoads = 0;
        RegisteredConfig<WorldConfig> original = create(WorldConfig.class, directory);
        Path fallback = original.pathResolver().restartPathForConfig(WorldConfig.class);
        Files.createDirectories(fallback.getParent());
        Files.writeString(fallback, "{ restart: 5, immediate: 'local' }");
        leaveCommittedRestartJournal(directory, fallback, "{ restart: 8, immediate: 'local' }");
        assertTrue(Files.readString(fallback).contains("restart: 5"));
        ConfigRegistryIsolation.beforeGameStartup();
        try (ConfigHolder<WorldConfig> started = LiteConfig.holder(WorldConfig.class)
            .modId("mod").baseDir(directory).create()) {
            assertEquals(5, started.data().restart);
            assertEquals("local", started.data().immediate);
            assertFalse(started.isActive());
            assertTrue(started.path().isEmpty());
            assertTrue(started.pending().isEmpty());
            assertEquals(0, WorldConfig.afterLoads);
            assertTrue(Files.readString(fallback).contains("restart: 5"));
        }
    }

    @Test
    void malformedJournalNeverFallsBackSilently(@TempDir Path directory) throws Exception {
        RestartSyncStore cache = store(directory, "server-a");
        cache.write(ConfigBytes.of(new byte[] {1}));
        Path path = cachePath(directory, "mod:Config", "server-a");
        Files.write(path.resolveSibling(path.getFileName() + ".txn"), new byte[] {0});
        assertThrows(LiteConfigException.class, cache::read);
    }

    @Test
    void replacingCommittedEnvelopesReclaimsUnusedMarkers(@TempDir Path directory) throws Exception {
        for (int value = 0; value < 8; value++) {
            RestartSyncStore.Transaction transaction = new RestartSyncStore.Transaction();
            transaction.stage(store(directory, "server-a"), ConfigBytes.of(new byte[] {(byte) value}));
            transaction.stage(store(directory, "server-b"), ConfigBytes.of(new byte[] {(byte) value}));
            transaction.commit();
        }
        try (java.util.stream.Stream<Path> files = Files.list(servers(directory).resolve(".transactions"))) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void restartSelectionIsInNormalFileBeforeDisconnectAndLoadsWithoutConnection(@TempDir Path directory)
        throws Exception {
        RegisteredConfig<StrictConfig> server = create(StrictConfig.class, directory.resolve("server"));
        RegisteredConfig<StrictConfig> client = create(StrictConfig.class, directory.resolve("client"));
        StrictConfig unsaved = client.state().copyOfCanonical();
        unsaved.local = "unsaved local preference";
        client.state().replace(unsaved);
        StrictConfig remote = server.state().copyOfCanonical();
        remote.restart = 8;
        remote.immediate = "remote";
        remote.local = "must not persist";
        server.state().replace(remote);
        SyncedConfig<StrictConfig> sync = SyncedConfig.of(client);
        ClientSyncResult result = receive(sync, SyncedConfig.of(server).cachedSnapshot().data());
        assertEquals(TranslationKey.RESTART_REQUIRED_SERVER, result.disconnectReason());
        assertEquals(5, client.state().published().restart);
        assertEquals("remote", client.state().published().immediate);
        assertEquals("unsaved local preference", client.state().published().local);
        assertEquals(8, client.state().pending().orElseThrow().restart);
        Path path = client.pathResolver().activePath().orElseThrow();
        String file = Files.readString(path);
        assertTrue(file.contains("8"));
        assertFalse(file.contains("remote"));
        assertFalse(file.contains("must not persist"));
        ConfigRegistryIsolation.beforeGameStartup();
        RegisteredConfig<StrictConfig> recreated = create(StrictConfig.class, directory.resolve("client"));
        assertEquals(8, recreated.state().published().restart);
        assertEquals("local", recreated.state().published().immediate);
        assertEquals("preference", recreated.state().published().local);
        assertFalse(recreated.state().remoteActive());
        assertTrue(recreated.state().pending().isEmpty());
    }

    @Test
    void sameProcessReconnectCannotPromoteDurableRestartSelection(@TempDir Path directory) {
        RegisteredConfig<RestartConfig> server = create(RestartConfig.class, directory.resolve("server"));
        RegisteredConfig<RestartConfig> client = create(RestartConfig.class, directory.resolve("client"));
        ConfigBytes bytes = payload(server, 8, "remote");
        SyncedConfig<RestartConfig> sync = SyncedConfig.of(client);
        assertTrue(receive(sync, bytes).restartRequired());
        sync.clearRemote();
        assertEquals(5, client.state().published().restart);
        assertEquals(8, client.state().pending().orElseThrow().restart);
        SyncedConfig<RestartConfig> reconnect = SyncedConfig.of(client);
        assertTrue(receive(reconnect, bytes).restartRequired());
        assertEquals(5, client.state().published().restart);
        assertEquals(8, client.state().pending().orElseThrow().restart);
    }

    @Test
    void pendingSurvivesLocalSaveUpdatesAndExplicitLoadWithoutHotActivation(@TempDir Path directory)
        throws Exception {
        RegisteredConfig<RestartConfig> server = create(RestartConfig.class, directory.resolve("server"));
        Path clients = directory.resolve("client");
        try (ConfigHolder<RestartConfig> holder = LiteConfig.holder(RestartConfig.class)
            .modId("mod").baseDir(clients).create()) {
            ConfigBytes bytes = payload(server, 8, "remote");
            ClientSyncResult result = ClientConfigSync.receivePayload(
                new ConfigSyncS2CPacket(0, true, true, Map.of(server.model().syncId(), bytes)));
            assertTrue(result.restartRequired());
            ClientConfigSync.resetClientConnection();
            assertEquals(8, holder.pending().orElseThrow().restart);
            holder.save();
            assertTrue(holder.update(value -> value.local = "in memory").accepted());
            assertEquals(8, holder.pending().orElseThrow().restart);
            assertTrue(holder.updateAndSave(value -> value.local = "saved preference").accepted());
            holder.load();
            assertEquals(5, holder.data().restart);
            assertEquals("local", holder.data().immediate);
            assertEquals("saved preference", holder.data().local);
            assertEquals(8, holder.pending().orElseThrow().restart);
            RegisteredConfig<RestartConfig> loaded = create(RestartConfig.class, clients);
            assertEquals(8, loaded.state().published().restart);
            assertEquals("local", loaded.state().published().immediate);
            assertEquals("saved preference", loaded.state().published().local);
        }
    }

    @Test
    void newerServerSelectionRequiresAnotherRestartInsteadOfCachePromotion(@TempDir Path directory) {
        RegisteredConfig<RestartConfig> server = create(RestartConfig.class, directory.resolve("server"));
        Path clients = directory.resolve("client");
        RegisteredConfig<RestartConfig> first = create(RestartConfig.class, clients);
        SyncedConfig<RestartConfig> sync = SyncedConfig.of(first);
        assertTrue(receive(sync, payload(server, 8, "first")).restartRequired());
        sync.clearRemote();
        RegisteredConfig<RestartConfig> next = create(RestartConfig.class, clients);
        assertEquals(8, next.state().published().restart);
        SyncedConfig<RestartConfig> nextSync = SyncedConfig.of(next);
        assertTrue(receive(nextSync, payload(server, 12, "next")).restartRequired());
        assertEquals(8, next.state().published().restart);
        nextSync.clearRemote();
        TestSyncPlatform.setConnectionIdentitySupplier(() -> "different-server");
        assertTrue(receive(nextSync, payload(server, 5, "other")).restartRequired());
        assertEquals(8, next.state().published().restart);
        assertEquals(5, next.state().pending().orElseThrow().restart);
    }

    @Test
    void matchingRestartAndImmediateOnlyPayloadsDoNotRunFileHooks(@TempDir Path directory) {
        RegisteredConfig<HookConfig> server = create(HookConfig.class, directory.resolve("server"));
        RegisteredConfig<HookConfig> client = create(HookConfig.class, directory.resolve("client"));
        HookConfig.beforeSaves = 0;
        HookConfig.afterLoads = 0;
        HookConfig remote = server.state().copyOfCanonical();
        remote.immediate = "remote";
        server.state().replace(remote);
        SyncedConfig<HookConfig> sync = SyncedConfig.of(client);
        ConfigBytes bytes = SyncedConfig.of(server).cachedSnapshot().data();
        assertFalse(receive(sync, bytes).restartRequired());
        assertEquals(0, HookConfig.beforeSaves);
        assertEquals(0, HookConfig.afterLoads);
        assertEquals(bytes, new RestartSyncStore(directory.resolve("client"), new ConfigScope("mod"),
            sync.id(), ClientConfigSync.connectionIdentity()).read());
    }

    @Test
    void configsWithoutRestartFieldsAlsoPersistFullWirePayload(@TempDir Path directory) {
        RegisteredConfig<ImmediateConfig> server = create(ImmediateConfig.class, directory.resolve("server"));
        RegisteredConfig<ImmediateConfig> client = create(ImmediateConfig.class, directory.resolve("client"));
        ImmediateConfig remote = server.state().copyOfCanonical();
        remote.value = "remote";
        server.state().replace(remote);
        SyncedConfig<ImmediateConfig> sync = SyncedConfig.of(client);
        ConfigBytes bytes = SyncedConfig.of(server).cachedSnapshot().data();
        assertNull(receive(sync, bytes).disconnectReason());
        assertEquals(bytes, new RestartSyncStore(directory.resolve("client"), new ConfigScope("mod"),
            sync.id(), ClientConfigSync.connectionIdentity()).read());
        assertEquals("local", create(ImmediateConfig.class, directory.resolve("client")).state().published().value);
    }

    @Test
    void differingRestartPayloadRunsOnlyTheRequiredFileSaveHook(@TempDir Path directory) {
        RegisteredConfig<HookConfig> server = create(HookConfig.class, directory.resolve("server"));
        RegisteredConfig<HookConfig> client = create(HookConfig.class, directory.resolve("client"));
        HookConfig remote = server.state().copyOfCanonical();
        remote.restart = 8;
        server.state().replace(remote);
        HookConfig.beforeSaves = 0;
        HookConfig.afterLoads = 0;
        assertTrue(receive(SyncedConfig.of(client),
            SyncedConfig.of(server).cachedSnapshot().data()).restartRequired());
        assertEquals(1, HookConfig.beforeSaves);
        assertEquals(0, HookConfig.afterLoads);
    }

    @Test
    void identicalCachedReplayKeepsFilesAndHooksUnchangedWithoutDroppingAuthority(@TempDir Path directory)
        throws Exception {
        RegisteredConfig<HookConfig> server = create(HookConfig.class, directory.resolve("server"));
        Path clients = directory.resolve("client");
        RegisteredConfig<HookConfig> client = create(HookConfig.class, clients);
        HookConfig remote = server.state().copyOfCanonical();
        remote.restart = 8;
        remote.immediate = "remote";
        server.state().replace(remote);
        SyncedConfig<HookConfig> sync = SyncedConfig.of(client);
        ConfigBytes payload = SyncedConfig.of(server).cachedSnapshot().data();
        assertTrue(receive(sync, payload).restartRequired());
        Path normal = client.pathResolver().activePath().orElseThrow();
        Path cached = cachePath(clients, sync.id(), ClientConfigSync.connectionIdentity());
        Path journal = cached.resolveSibling(cached.getFileName() + ".txn");
        FileTime sentinel = FileTime.fromMillis(946684800000L);
        Files.setLastModifiedTime(normal, sentinel);
        Files.setLastModifiedTime(cached, sentinel);
        Files.setLastModifiedTime(journal, sentinel);
        HookConfig.beforeSaves = 0;
        HookConfig.afterLoads = 0;
        AtomicInteger updates = new AtomicInteger();
        try (ConfigHolder<HookConfig> holder = client.access()) {
            holder.onUpdate(ignored -> updates.incrementAndGet());
            ClientSyncResult repeated = new ConfigSyncTransactions(id -> id.equals(sync.id()) ? sync : null)
                .receiveCached(Map.of(sync.id(), payload), true);
            assertTrue(repeated.restartRequired());
            assertNull(repeated.disconnectReason());
            assertTrue(client.state().remoteActive());
            assertEquals(5, holder.data().restart);
            assertEquals(8, holder.pending().orElseThrow().restart);
            assertEquals(0, updates.get());
        }
        assertEquals(0, HookConfig.beforeSaves);
        assertEquals(0, HookConfig.afterLoads);
        assertEquals(sentinel, Files.getLastModifiedTime(normal));
        assertEquals(sentinel, Files.getLastModifiedTime(cached));
        assertEquals(sentinel, Files.getLastModifiedTime(journal));
    }

    @Test
    void failingSaveHookRollsBackStateNormalFileAndCache(@TempDir Path directory) throws Exception {
        RegisteredConfig<HookConfig> server = create(HookConfig.class, directory.resolve("server"));
        RegisteredConfig<HookConfig> client = create(HookConfig.class, directory.resolve("client"));
        HookConfig remote = server.state().copyOfCanonical();
        remote.restart = 8;
        server.state().replace(remote);
        SyncedConfig<HookConfig> sync = SyncedConfig.of(client);
        Path path = client.pathResolver().activePath().orElseThrow();
        String previous = Files.readString(path);
        HookConfig.failSave = true;
        try {
            assertEquals(TranslationKey.SYNC_FAILED,
                receive(sync, SyncedConfig.of(server).cachedSnapshot().data()).disconnectReason());
        } finally {
            HookConfig.failSave = false;
        }
        assertEquals(5, client.state().published().restart);
        assertFalse(client.state().remoteActive());
        assertTrue(client.state().pending().isEmpty());
        assertEquals(previous, Files.readString(path));
        assertNull(new RestartSyncStore(directory.resolve("client"), new ConfigScope("mod"),
            sync.id(), ClientConfigSync.connectionIdentity()).read());
    }

    @Test
    void laterConfigSaveFailureRollsBackEarlierPublishedConfigAndAllFiles(@TempDir Path directory)
        throws Exception {
        Path clients = directory.resolve("client");
        RegisteredConfig<RestartConfig> first = create(RestartConfig.class, clients);
        RegisteredConfig<HookConfig> second = create(HookConfig.class, clients);
        RegisteredConfig<RestartConfig> firstServer = create(RestartConfig.class, directory.resolve("server"));
        RegisteredConfig<HookConfig> secondServer = create(HookConfig.class, directory.resolve("server"));
        HookConfig remote = secondServer.state().copyOfCanonical();
        remote.restart = 8;
        secondServer.state().replace(remote);
        SyncedConfig<RestartConfig> firstSync = SyncedConfig.of(first);
        SyncedConfig<HookConfig> secondSync = SyncedConfig.of(second);
        Map<String, SyncedConfig<?>> registrations = Map.of(firstSync.id(), firstSync, secondSync.id(), secondSync);
        Map<String, ConfigBytes> entries = new LinkedHashMap<>();
        entries.put(firstSync.id(), payload(firstServer, 8, "remote"));
        entries.put(secondSync.id(), SyncedConfig.of(secondServer).cachedSnapshot().data());
        String firstFile = Files.readString(first.pathResolver().activePath().orElseThrow());
        String secondFile = Files.readString(second.pathResolver().activePath().orElseThrow());
        HookConfig.failSave = true;
        try {
            assertEquals(TranslationKey.SYNC_FAILED, new ConfigSyncTransactions(registrations::get)
                .receive(new ConfigSyncS2CPacket(0, true, true, entries)).disconnectReason());
        } finally {
            HookConfig.failSave = false;
        }
        assertFalse(first.state().remoteActive());
        assertFalse(second.state().remoteActive());
        assertEquals(5, first.state().published().restart);
        assertEquals("local", first.state().published().immediate);
        assertTrue(first.state().pending().isEmpty());
        assertEquals(firstFile, Files.readString(first.pathResolver().activePath().orElseThrow()));
        assertEquals(secondFile, Files.readString(second.pathResolver().activePath().orElseThrow()));
        assertEquals(5, create(RestartConfig.class, clients).state().published().restart);
        assertNull(new RestartSyncStore(clients, new ConfigScope("mod"), firstSync.id(),
            ClientConfigSync.connectionIdentity()).read());
    }

    @Test
    void unwritableNormalTargetIsNotReportedAsSuccessfulRestart(@TempDir Path directory) throws Exception {
        RegisteredConfig<RestartConfig> server = create(RestartConfig.class, directory.resolve("server"));
        RegisteredConfig<RestartConfig> client = create(RestartConfig.class, directory.resolve("client"));
        Path normal = client.pathResolver().activePath().orElseThrow();
        Files.delete(normal);
        Files.createDirectory(normal);
        SyncedConfig<RestartConfig> sync = SyncedConfig.of(client);
        assertEquals(TranslationKey.SYNC_FAILED, receive(sync, payload(server, 8, "remote")).disconnectReason());
        assertFalse(client.state().remoteActive());
        assertTrue(client.state().pending().isEmpty());
    }

    @Test
    void publicationFailureAfterStagingCannotCommitAnyRestartFile(@TempDir Path directory)
        throws Exception {
        RegisteredConfig<PublishConfig> server = create(PublishConfig.class, directory.resolve("server"));
        Path clients = directory.resolve("client");
        RegisteredConfig<PublishConfig> client = create(PublishConfig.class, clients);
        PublishConfig remote = server.state().copyOfCanonical();
        remote.restart = 8;
        remote.immediate = "reject";
        server.state().replace(remote);
        SyncedConfig<PublishConfig> sync = SyncedConfig.of(client);
        ConfigBytes bytes = SyncedConfig.of(server).cachedSnapshot().data();
        String previous = Files.readString(client.pathResolver().activePath().orElseThrow());
        PublishCloner.fail = true;
        try {
            assertEquals(TranslationKey.SYNC_FAILED, receive(sync, bytes).disconnectReason());
        } finally {
            PublishCloner.fail = false;
        }
        assertFalse(client.state().remoteActive());
        assertEquals(5, client.state().published().restart);
        assertEquals("local", client.state().published().immediate);
        assertTrue(client.state().pending().isEmpty());
        assertEquals(previous, Files.readString(client.pathResolver().activePath().orElseThrow()));
        assertEquals(5, create(PublishConfig.class, clients).state().published().restart);
        assertNull(new RestartSyncStore(clients, new ConfigScope("mod"), sync.id(),
            ClientConfigSync.connectionIdentity()).read());
    }

    @Test
    void restartPersistenceRunsSaveHookOnLocalCopyThenNormalizesAndValidates(@TempDir Path directory) {
        RegisteredConfig<NormalizedConfig> server = create(NormalizedConfig.class, directory.resolve("server"));
        Path clients = directory.resolve("client");
        RegisteredConfig<NormalizedConfig> client = create(NormalizedConfig.class, clients);
        NormalizedConfig remote = server.state().copyOfCanonical();
        remote.mode = "FAST";
        remote.immediate = "server";
        server.state().replace(remote);
        SyncedConfig<NormalizedConfig> sync = SyncedConfig.of(client);
        assertTrue(receive(sync, SyncedConfig.of(server).cachedSnapshot().data()).restartRequired());
        assertEquals("safe", client.state().published().mode);
        assertEquals("server", client.state().published().immediate);
        assertEquals("fast", client.state().pending().orElseThrow().mode);
        RegisteredConfig<NormalizedConfig> recreated = create(NormalizedConfig.class, clients);
        assertEquals("fast", recreated.state().published().mode);
        assertEquals("local", recreated.state().published().immediate);
        assertEquals("hook", recreated.state().published().local);
    }

    @Test
    void validationOfThePreparedLocalFileCanAbortAnOtherwiseValidRemoteSnapshot(@TempDir Path directory)
        throws Exception {
        RegisteredConfig<NormalizedConfig> server = create(NormalizedConfig.class, directory.resolve("server"));
        Path clients = directory.resolve("client");
        RegisteredConfig<NormalizedConfig> client = create(NormalizedConfig.class, clients);
        NormalizedConfig remote = server.state().copyOfCanonical();
        remote.mode = "fast";
        server.state().replace(remote);
        SyncedConfig<NormalizedConfig> sync = SyncedConfig.of(client);
        String previous = Files.readString(client.pathResolver().activePath().orElseThrow());
        NormalizedConfig.invalidSave = true;
        try {
            assertEquals(TranslationKey.SYNC_FAILED,
                receive(sync, SyncedConfig.of(server).cachedSnapshot().data()).disconnectReason());
        } finally {
            NormalizedConfig.invalidSave = false;
        }
        assertEquals("safe", client.state().published().mode);
        assertFalse(client.state().remoteActive());
        assertEquals(previous, Files.readString(client.pathResolver().activePath().orElseThrow()));
    }

    @Test
    void cacheFailureIsExplicitEvenWithoutRestartFields(@TempDir Path directory) throws Exception {
        RegisteredConfig<ImmediateConfig> server = create(ImmediateConfig.class, directory.resolve("server"));
        Path clients = directory.resolve("client");
        RegisteredConfig<ImmediateConfig> client = create(ImmediateConfig.class, clients);
        Files.createDirectories(clients.resolve("liteconfig"));
        Files.writeString(servers(clients), "blocks cache");
        SyncedConfig<ImmediateConfig> sync = SyncedConfig.of(client);
        assertEquals(TranslationKey.SYNC_FAILED,
            receive(sync, SyncedConfig.of(server).cachedSnapshot().data()).disconnectReason());
        assertFalse(client.state().remoteActive());
    }

    @Test
    void unboundWorldAppliesInitialSyncWithoutWritingAnInstallationFallback(@TempDir Path directory)
        throws Exception {
        WorldConfig.afterLoads = 0;
        RegisteredConfig<WorldConfig> server = create(WorldConfig.class, directory.resolve("server"));
        WorldConfig remote = server.state().copyOfCanonical();
        remote.restart = 8;
        remote.immediate = "remote";
        server.state().replace(remote);
        Path clients = directory.resolve("client");
        RegisteredConfig<WorldConfig> client = create(WorldConfig.class, clients);
        assertEquals(0, WorldConfig.afterLoads);
        SyncedConfig<WorldConfig> sync = SyncedConfig.of(client);
        assertFalse(receive(sync, SyncedConfig.of(server).cachedSnapshot().data()).restartRequired());
        assertEquals(8, client.state().published().restart);
        assertTrue(client.state().pending().isEmpty());
        assertTrue(client.pathResolver().activePath().isEmpty());
        Path fallback = client.pathResolver().restartPathForConfig(WorldConfig.class);
        assertTrue(fallback.startsWith(servers(clients).resolve("world")));
        assertFalse(Files.exists(fallback));
        ConfigRegistryIsolation.beforeGameStartup();
        RegisteredConfig<WorldConfig> recreated = create(WorldConfig.class, clients);
        assertEquals(0, WorldConfig.afterLoads);
        assertEquals(5, recreated.state().published().restart);
        assertEquals("local", recreated.state().published().immediate);
        assertFalse(recreated.isActive());
        assertTrue(recreated.pathResolver().activePath().isEmpty());
    }

    @Test
    void remoteWorldRestartFallbackCannotOverwriteANewlyBoundAuthoritativeSave(@TempDir Path directory)
        throws Exception {
        RegisteredConfig<WorldConfig> server = create(WorldConfig.class, directory.resolve("server"));
        WorldConfig remote = server.state().copyOfCanonical();
        remote.restart = 8;
        remote.immediate = "remote";
        server.state().replace(remote);
        Path clients = directory.resolve("client");
        Path world = directory.resolve("local-world");
        Path authoritative = world.resolve("liteconfig").resolve("mod").resolve("world.json5");
        Files.createDirectories(authoritative.getParent());
        Files.writeString(authoritative, "{ restart: 11, immediate: 'local world' }");
        try (ConfigHolder<WorldConfig> holder = LiteConfig.holder(WorldConfig.class)
            .modId("mod").baseDir(clients).create()) {
            ClientSyncResult result = ClientConfigSync.receivePayload(new ConfigSyncS2CPacket(
                0, true, true, Map.of(server.model().syncId(), SyncedConfig.of(server).cachedSnapshot().data())));
            assertFalse(result.restartRequired());
            remote.restart = 9;
            server.state().replace(remote);
            result = ClientConfigSync.receivePayload(new ConfigSyncS2CPacket(
                0, true, false, Map.of(server.model().syncId(), SyncedConfig.of(server).cachedSnapshot().data())));
            assertTrue(result.restartRequired());
            assertEquals(8, holder.data().restart);
            assertEquals(9, holder.pending().orElseThrow().restart);
            ClientConfigSync.resetClientConnection();
            assertEquals(5, holder.data().restart);
            assertTrue(holder.pending().isEmpty());
            ConfigRegistry.activateWorld(world);
            assertEquals(11, holder.data().restart);
            assertEquals("local world", holder.data().immediate);
            holder.save();
            holder.load();
            assertEquals(11, holder.data().restart);
            assertTrue(holder.pending().isEmpty());
            assertTrue(Files.readString(authoritative).contains("\"restart\": 11"));
            ConfigRegistry.deactivateWorld();
            assertEquals(5, holder.data().restart);
            assertTrue(Files.readString(authoritative).contains("\"restart\": 11"));
            assertEquals(5, create(WorldConfig.class, clients).state().published().restart);
        }
    }

    private static <T> RegisteredConfig<T> create(Class<T> type, Path directory) {
        return RegisteredConfigs.create(new ConfigSettings<>(type, new ConfigScope("mod"), directory));
    }

    private static void leaveCommittedRestartJournal(Path directory, Path file, String selected)
        throws Exception {
        byte[] previous = Files.readAllBytes(file);
        RestartSyncStore.Transaction transaction = new RestartSyncStore.Transaction();
        transaction.stageFile(RestartSyncStore.forFile(directory, file, new ConfigScope("mod")),
            selected.getBytes(StandardCharsets.UTF_8));
        Files.delete(file);
        Files.createDirectory(file);
        Path obstruction = file.resolve("obstruction");
        Files.writeString(obstruction, "blocks materialization after the durable commit");
        transaction.commit();
        Files.delete(obstruction);
        Files.delete(file);
        Files.write(file, previous);
    }

    private static ConfigBytes payload(RegisteredConfig<RestartConfig> server, int restart, String immediate) {
        RestartConfig remote = server.state().copyOfCanonical();
        remote.restart = restart;
        remote.immediate = immediate;
        remote.local = "server preference";
        server.state().replace(remote);
        return SyncedConfig.of(server).cachedSnapshot().data();
    }

    private static ClientSyncResult receive(SyncedConfig<?> sync, ConfigBytes payload) {
        return new ConfigSyncTransactions(id -> id.equals(sync.id()) ? sync : null)
            .receive(new ConfigSyncS2CPacket(0, true, true, Map.of(sync.id(), payload)));
    }

    private static RestartSyncStore store(Path directory, String server) {
        return new RestartSyncStore(directory, new ConfigScope("mod"), "mod:Config", server);
    }

    private static Path servers(Path directory) {
        return directory.resolve("liteconfig").resolve("servers");
    }

    private static Path cachePath(Path directory, String id, String server) {
        String key = Hashing.sha256().hashString(id + "\n" + server, StandardCharsets.UTF_8).toString();
        return servers(directory).resolve(key + ".bin");
    }

    @Config(name = "restart", sync = SyncMode.ENABLED)
    public static class RestartConfig {
        @Entry(restart = true)
        public int restart = 5;
        public String immediate = "local";
        @Entry(sync = SyncMode.DISABLED)
        public String local = "preference";
    }

    @Config(name = "strict", sync = SyncMode.ENABLED, restartSyncPolicy = RestartSyncPolicy.DISCONNECT)
    public static class StrictConfig {
        @Entry(restart = true)
        public int restart = 5;
        public String immediate = "local";
        @Entry(sync = SyncMode.DISABLED)
        public String local = "preference";
    }

    @Config(name = "hook", sync = SyncMode.ENABLED)
    public static class HookConfig implements ConfigExtension {
        static boolean failSave;
        static int beforeSaves;
        static int afterLoads;
        @Entry(restart = true)
        public int restart = 5;
        public String immediate = "local";

        @Override
        public void beforeSave() {
            beforeSaves++;
            if (failSave) {
                throw new IllegalStateException("injected save failure");
            }
        }

        @Override
        public void afterLoad() {
            afterLoads++;
        }
    }

    @Config(name = "immediate", sync = SyncMode.ENABLED)
    public static class ImmediateConfig {
        public String value = "local";
    }

    @Config(name = "publish", sync = SyncMode.ENABLED, stateCloner = PublishCloner.class)
    public static class PublishConfig {
        @Entry(restart = true)
        public int restart = 5;
        public String immediate = "local";
    }

    public static class PublishCloner implements StateCloner<PublishConfig> {
        static boolean fail;

        @Override
        public PublishConfig copy(PublishConfig source) {
            if (fail && source.restart == 5 && source.immediate.equals("reject")) {
                throw new IllegalStateException("injected publication failure");
            }
            PublishConfig copy = new PublishConfig();
            copy.restart = source.restart;
            copy.immediate = source.immediate;
            return copy;
        }
    }

    @Config(name = "normalized", sync = SyncMode.ENABLED)
    public static class NormalizedConfig implements ConfigExtension {
        static boolean invalidSave;
        @Entry(restart = true)
        @AllowedValues({"safe", "fast"})
        public String mode = "safe";
        public String immediate = "local";
        @Entry(sync = SyncMode.DISABLED)
        @AllowedValues({"local", "hook", "invalid"})
        public String local = "local";

        @Override
        public void beforeSave() {
            local = invalidSave ? "INVALID" : "HOOK";
        }

        @Override
        public void validate(List<Violation> violations) {
            if (local.equals("invalid")) {
                violations.add(Violation.of("local.invalid", "cannot persist this hook result"));
            }
        }
    }

    @Config(name = "world", type = ConfigType.WORLD, sync = SyncMode.ENABLED)
    public static class WorldConfig implements ConfigExtension {
        static int afterLoads;
        @Entry(restart = true)
        public int restart = 5;
        public String immediate = "local";

        @Override
        public void afterLoad() {
            afterLoads++;
        }
    }
}
