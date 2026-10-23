package com.gmalvestiti.minecraft.liteconfig.network;

import com.gmalvestiti.minecraft.liteconfig.shared.TranslationKey;
import com.gmalvestiti.minecraft.liteconfig.api.ConfigHolder;
import com.gmalvestiti.minecraft.liteconfig.api.ConfigExtension;
import com.gmalvestiti.minecraft.liteconfig.api.LiteConfig;
import com.gmalvestiti.minecraft.liteconfig.api.RestartSyncPolicy;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Config;
import com.gmalvestiti.minecraft.liteconfig.api.SyncMode;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Entry;
import com.gmalvestiti.minecraft.liteconfig.api.spi.Violation;
import com.gmalvestiti.minecraft.liteconfig.context.ConfigSettings;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigScope;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigError;
import com.gmalvestiti.minecraft.liteconfig.exception.LiteConfigException;
import com.gmalvestiti.minecraft.liteconfig.network.packet.ConfigSyncS2CPacket;
import com.gmalvestiti.minecraft.liteconfig.registry.RegisteredConfig;
import com.gmalvestiti.minecraft.liteconfig.registry.ConfigRegistry;
import com.gmalvestiti.minecraft.liteconfig.support.RegisteredConfigs;
import com.gmalvestiti.minecraft.liteconfig.support.ConfigRegistryIsolation;
import com.gmalvestiti.minecraft.liteconfig.support.TestFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(ConfigRegistryIsolation.class)
class SyncedConfigTest {

    @Test
    void snapshotEncodingUsesTheStateMonitorWithoutStartingANestedOperation(@TempDir Path tempDir) {
        if (LiteConfig.codecs().find(SnapshotLockValue.class).isEmpty()) {
            LiteConfig.codecs()
                .registerCodec(SnapshotLockValue.class, SnapshotLockValue.CODEC)
                .registerStreamCodec(SnapshotLockValue.class, SnapshotLockValue.STREAM_CODEC);
        }
        RegisteredConfig<SnapshotLockConfig> config = create(SnapshotLockConfig.class, tempDir);
        SyncedConfig<SnapshotLockConfig> synced = SyncedConfig.of(config);
        AtomicInteger encodes = new AtomicInteger();
        SnapshotLockValue.PROBE = () -> {
            assertTrue(Thread.holdsLock(config.state().operationLock()));
            assertFalse(Thread.holdsLock(synced), "snapshot encoding must not acquire a second monitor");
            encodes.incrementAndGet();
        };
        try {
            synced.cachedSnapshot();
            config.state().writing(() -> {
                SnapshotLockConfig candidate = config.state().copyOfCanonical();
                candidate.value = new SnapshotLockValue(2);
                config.state().replace(candidate);
                assertTrue(synced.changedSnapshot() != null);
                return null;
            });
            assertEquals(2, encodes.get());
        } finally {
            SnapshotLockValue.PROBE = () -> {};
        }
    }

    @Test
    void testCarriesNestedValuesToAnotherCopyOfTheSameConfig(@TempDir Path tempDir) {
        RegisteredConfig<TestFixtures.SyncedConfig> source =
            create(TestFixtures.SyncedConfig.class, tempDir.resolve("source"));
        RegisteredConfig<TestFixtures.SyncedConfig> target =
            create(TestFixtures.SyncedConfig.class, tempDir.resolve("target"));

        TestFixtures.SyncedConfig candidate = source.state().copyOfCanonical();
        candidate.maxTeamSize = 9;
        candidate.strictMode = !candidate.strictMode;
        candidate.section.strict = !candidate.section.strict;
        source.state().replace(candidate);

        apply(SyncedConfig.of(target), SyncedConfig.of(source).cachedSnapshot().data());

        assertEquals(9, target.state().published().maxTeamSize);
        assertEquals(candidate.strictMode, target.state().published().strictMode);
        assertEquals(candidate.section.strict, target.state().published().section.strict);
    }

    @Test
    void testCarriesEveryLeafTypeItSupports(@TempDir Path tempDir) {
        RegisteredConfig<WireTypesConfig> source =
            create(WireTypesConfig.class, tempDir.resolve("source"));
        RegisteredConfig<WireTypesConfig> target =
            create(WireTypesConfig.class, tempDir.resolve("target"));

        WireTypesConfig candidate = source.state().copyOfCanonical();
        candidate.flag = false;
        candidate.tiny = -8;
        candidate.small = -300;
        candidate.letter = 'z';
        candidate.count = -42;
        candidate.big = Long.MIN_VALUE;
        candidate.ratio = 0.25f;
        candidate.precise = -1.5d;
        candidate.label = "changed";
        candidate.mood = Mood.CALM;
        source.state().replace(candidate);

        apply(SyncedConfig.of(target), SyncedConfig.of(source).cachedSnapshot().data());

        WireTypesConfig published = target.state().published();
        assertEquals(false, published.flag);
        assertEquals((byte) -8, published.tiny);
        assertEquals((short) -300, published.small);
        assertEquals('z', published.letter);
        assertEquals(-42, published.count);
        assertEquals(Long.MIN_VALUE, published.big);
        assertEquals(0.25f, published.ratio);
        assertEquals(-1.5d, published.precise);
        assertEquals("changed", published.label);
        assertEquals(Mood.CALM, published.mood);
    }

    @Test
    void testCarriesNullThroughAReferenceValue(@TempDir Path tempDir) {
        RegisteredConfig<WireTypesConfig> source =
            create(WireTypesConfig.class, tempDir.resolve("source"));
        RegisteredConfig<WireTypesConfig> target =
            create(WireTypesConfig.class, tempDir.resolve("target"));

        WireTypesConfig candidate = source.state().copyOfCanonical();
        candidate.label = null;
        source.state().replace(candidate);

        apply(SyncedConfig.of(target), SyncedConfig.of(source).cachedSnapshot().data());

        assertNull(target.state().published().label);
    }

    @Test
    void testCarriesARegisteredCustomValue(@TempDir Path tempDir) {
        registerCustomWireCodec();

        RegisteredConfig<CustomWireConfig> source =
            create(CustomWireConfig.class, tempDir.resolve("source"));
        RegisteredConfig<CustomWireConfig> target =
            create(CustomWireConfig.class, tempDir.resolve("target"));

        CustomWireConfig candidate = source.state().copyOfCanonical();
        candidate.color = new RgbColor(7, 8);
        source.state().replace(candidate);

        apply(SyncedConfig.of(target), SyncedConfig.of(source).cachedSnapshot().data());

        assertEquals(new RgbColor(7, 8), target.state().published().color);
    }

    @Test
    void testCarriesAValueRegisteredWithVanillaCodecs(@TempDir Path tempDir) {
        if (LiteConfig.codecs().find(IntRange.class).isEmpty()) {
            LiteConfig.codecs()
                .registerCodec(IntRange.class, IntRange.CODEC)
                .registerStreamCodec(IntRange.class, IntRange.STREAM_CODEC);
        }

        RegisteredConfig<VanillaCodecConfig> source =
            create(VanillaCodecConfig.class, tempDir.resolve("source"));
        RegisteredConfig<VanillaCodecConfig> target =
            create(VanillaCodecConfig.class, tempDir.resolve("target"));

        VanillaCodecConfig candidate = source.state().copyOfCanonical();
        candidate.range = new IntRange(4, 20);
        source.state().replace(candidate);

        apply(SyncedConfig.of(target), SyncedConfig.of(source).cachedSnapshot().data());

        assertEquals(new IntRange(4, 20), target.state().published().range);
    }

    @Test
    void testCarriesListsMapsAndNestedCustomValues(@TempDir Path tempDir) {
        registerCustomWireCodec();

        RegisteredConfig<CollectionWireConfig> source =
            create(CollectionWireConfig.class, tempDir.resolve("source"));
        RegisteredConfig<CollectionWireConfig> target =
            create(CollectionWireConfig.class, tempDir.resolve("target"));

        CollectionWireConfig candidate = source.state().copyOfCanonical();
        candidate.labels = List.of("one", "two");
        candidate.weights = new LinkedHashMap<>();
        candidate.weights.put("first", 4);
        candidate.weights.put("second", 8);
        candidate.colors = Map.of(
            "warm", List.of(new RgbColor(7, 8), new RgbColor(9, 10)));
        candidate.tags = Set.of("server", "required");
        source.state().replace(candidate);

        apply(SyncedConfig.of(target), SyncedConfig.of(source).cachedSnapshot().data());

        CollectionWireConfig published = target.state().published();
        assertEquals(List.of("one", "two"), published.labels);
        assertEquals(Map.of("first", 4, "second", 8), published.weights);
        assertEquals(
            Map.of("warm", List.of(new RgbColor(7, 8), new RgbColor(9, 10))),
            published.colors);
        assertEquals(Set.of("server", "required"), published.tags);
        assertTrue(published.tags instanceof LinkedHashSet);
    }

    @Test
    void testCanonicalizesSetOrderOnTheWire(@TempDir Path tempDir) {
        RegisteredConfig<CollectionWireConfig> config =
            create(CollectionWireConfig.class, tempDir);

        CollectionWireConfig first = config.state().copyOfCanonical();
        first.tags = new LinkedHashSet<>(List.of("first", "second"));
        config.state().replace(first);
        ConfigBytes firstPayload = SyncedConfig.of(config).cachedSnapshot().data();

        CollectionWireConfig second = config.state().copyOfCanonical();
        second.tags = new LinkedHashSet<>(List.of("second", "first"));
        config.state().replace(second);
        ConfigBytes secondPayload = SyncedConfig.of(config).cachedSnapshot().data();

        assertEquals(firstPayload, secondPayload);
    }

    @Test
    void testCarriesLargeCollections(@TempDir Path tempDir) {
        RegisteredConfig<CollectionWireConfig> source =
            create(CollectionWireConfig.class, tempDir.resolve("source"));
        RegisteredConfig<CollectionWireConfig> target =
            create(CollectionWireConfig.class, tempDir.resolve("target"));

        CollectionWireConfig candidate = source.state().copyOfCanonical();
        candidate.labels = Collections.nCopies(1025, "value");
        source.state().replace(candidate);

        apply(SyncedConfig.of(target), SyncedConfig.of(source).cachedSnapshot().data());

        assertEquals(candidate.labels, target.state().published().labels);
    }

    @Test
    void testInvokesListenersAndEventsOnlyForChangedSyncValues(@TempDir Path tempDir) {
        ListenerSyncedConfig.EVENTS.clear();

        RegisteredConfig<ListenerSyncedConfig> source =
            create(ListenerSyncedConfig.class, tempDir.resolve("source"));
        RegisteredConfig<ListenerSyncedConfig> target =
            create(ListenerSyncedConfig.class, tempDir.resolve("target"));
        int[] updates = {0};

        ListenerSyncedConfig candidate = source.state().copyOfCanonical();
        candidate.shared = 6;
        source.state().replace(candidate);

        ConfigBytes payload = SyncedConfig.of(source).cachedSnapshot().data();
        target.holderEvents().addUpdateEvent(state -> updates[0]++);

        apply(SyncedConfig.of(target), payload);
        apply(SyncedConfig.of(target), payload);

        assertEquals(List.of(new SyncListenerEvent(1, 6, true)), ListenerSyncedConfig.EVENTS);
        assertEquals(1, updates[0]);
    }

    @Test
    void testAppliesOverlayAndDispatchesListenersOnCallerThread(
        @TempDir Path tempDir
    ) throws Exception {
        ListenerSyncedConfig.EVENTS.clear();
        ListenerSyncedConfig.THREADS.clear();

        RegisteredConfig<ListenerSyncedConfig> source =
            create(ListenerSyncedConfig.class, tempDir.resolve("source"));


        RegisteredConfig<ListenerSyncedConfig> target =
            ConfigRegistry.register(new ConfigSettings<>(
                ListenerSyncedConfig.class, new ConfigScope("mod"), tempDir.resolve("target")));

        ListenerSyncedConfig candidate = source.state().copyOfCanonical();
        candidate.shared = 8;
        source.state().replace(candidate);

        ClientSyncResult result = ClientConfigSync.receivePayload(
            new ConfigSyncS2CPacket(
                true,
                Map.of(
                    target.model().syncId(),
                    SyncedConfig.of(source).cachedSnapshot().data()
                )
            )
        );
        assertTrue(result.completed());
        assertNull(result.disconnectReason());

        assertEquals(List.of(Thread.currentThread().getName()), ListenerSyncedConfig.THREADS);
        assertTrue(Files.readString(tempDir.resolve("target").resolve("mod").resolve("listener-sync.json5"))
            .contains("\"shared\": 1"));
    }

    @Test
    void testSyncedListenersAndResetRestorationCompleteSynchronously(@TempDir Path tempDir) {
        ListenerSyncedConfig.EVENTS.clear();

        RegisteredConfig<ListenerSyncedConfig> source =
            create(ListenerSyncedConfig.class, tempDir.resolve("source"));
        RegisteredConfig<ListenerSyncedConfig> target =
            ConfigRegistry.register(new ConfigSettings<>(
                ListenerSyncedConfig.class, new ConfigScope("mod"), tempDir.resolve("target")));

        ListenerSyncedConfig candidate = source.state().copyOfCanonical();
        candidate.shared = 7;
        source.state().replace(candidate);

        AtomicInteger notifications = new AtomicInteger();
        target.holderEvents().addUpdateEvent(ignored -> notifications.incrementAndGet());

        apply(SyncedConfig.of(target), SyncedConfig.of(source).cachedSnapshot().data());
        assertEquals(List.of(new SyncListenerEvent(1, 7, true)), ListenerSyncedConfig.EVENTS);
        assertEquals(1, notifications.get());

        ClientConfigSync.resetClientConnection();

        assertEquals(List.of(
            new SyncListenerEvent(1, 7, true),
            new SyncListenerEvent(7, 1, true)), ListenerSyncedConfig.EVENTS);
        assertEquals(2, notifications.get());
        assertEquals(1, target.state().published().shared);
        assertFalse(target.state().remoteActive());
        ClientConfigSync.resetClientConnection();
        assertEquals(2, notifications.get());
    }

    @Test
    void testSyncedFieldListenerRunsOutsideMonitorAndPreservesRemoteUpdatePolicy(@TempDir Path tempDir) {
        RegisteredConfig<ListenerSyncedConfig> source =
            create(ListenerSyncedConfig.class, tempDir.resolve("source"));
        RegisteredConfig<ListenerSyncedConfig> target =
            create(ListenerSyncedConfig.class, tempDir.resolve("target"));
        ConfigHolder<ListenerSyncedConfig> holder = target.access();
        ListenerSyncedConfig candidate = source.state().copyOfCanonical();
        candidate.shared = 7;
        source.state().replace(candidate);
        SyncedConfig<ListenerSyncedConfig> synced = SyncedConfig.of(target);
        List<Boolean> monitorHeld = new ArrayList<>();
        List<Boolean> updateAccepted = new ArrayList<>();
        ListenerSyncedConfig.AFTER_RECORD = () -> {
            monitorHeld.add(Thread.holdsLock(target.state().operationLock()));
            updateAccepted.add(holder.update(value -> {}).accepted());
        };
        try {
            apply(synced, SyncedConfig.of(source).cachedSnapshot().data());
            synced.clearRemote();
        } finally {
            ListenerSyncedConfig.AFTER_RECORD = () -> {
            };
            synced.clearRemote();
        }

        assertEquals(List.of(false, false), monitorHeld);
        assertEquals(List.of(false, true), updateAccepted);
        assertEquals(1, holder.data().shared);
    }

    @Test
    void testClientSyncNotifiesHolderEventsWithoutUsingThemForBroadcast(@TempDir Path tempDir) {
        RegisteredConfig<ListenerSyncedConfig> source =
            create(ListenerSyncedConfig.class, tempDir.resolve("source"));
        RegisteredConfig<ListenerSyncedConfig> target =
            create(ListenerSyncedConfig.class, tempDir.resolve("target"));

        ListenerSyncedConfig candidate = source.state().copyOfCanonical();
        candidate.shared = 7;
        source.state().replace(candidate);

        AtomicInteger allUpdates = new AtomicInteger();
        SyncedConfig<ListenerSyncedConfig> synced = SyncedConfig.of(target);
        synced.registerLocalChangeBroadcast();
        target.holderEvents().addUpdateEvent(ignored -> allUpdates.incrementAndGet());

        apply(synced, SyncedConfig.of(source).cachedSnapshot().data());

        assertEquals(1, allUpdates.get());
        target.syncNotifier().notifyLocalChange();
        assertEquals(1, allUpdates.get());
    }

    @Test
    void testListenersReceiveEachPublishedTransition(@TempDir Path tempDir) {
        RegisteredConfig<ListenerSyncedConfig> source =
            create(ListenerSyncedConfig.class, tempDir.resolve("source"));
        RegisteredConfig<ListenerSyncedConfig> target =
            create(ListenerSyncedConfig.class, tempDir.resolve("target"));
        List<Integer> published = new ArrayList<>();
        target.holderEvents().addUpdateEvent(state -> published.add(state.shared));

        ListenerSyncedConfig second = source.state().copyOfCanonical();
        second.shared = 2;
        source.state().replace(second);

        apply(SyncedConfig.of(target), SyncedConfig.of(source).cachedSnapshot().data());

        ListenerSyncedConfig third = source.state().copyOfCanonical();
        third.shared = 3;
        source.state().replace(third);

        apply(SyncedConfig.of(target), SyncedConfig.of(source).cachedSnapshot().data());

        assertEquals(List.of(2, 3), published);
    }

    @Test
    void testSerializesListenersInTransitionOrder(@TempDir Path tempDir) {
        ListenerSyncedConfig.EVENTS.clear();
        ListenerSyncedConfig.ACTIVE.set(0);
        ListenerSyncedConfig.MAX_ACTIVE.set(0);
        RegisteredConfig<ListenerSyncedConfig> config =
            create(ListenerSyncedConfig.class, tempDir);

        ListenerSyncedConfig first = new ListenerSyncedConfig();
        ListenerSyncedConfig second = new ListenerSyncedConfig();
        ListenerSyncedConfig third = new ListenerSyncedConfig();
        second.shared = 2;
        third.shared = 3;

        Runnable firstDrain = config.model().listeners().enqueueChanged(first, second, false);
        Runnable secondDrain = config.model().listeners().enqueueChanged(second, third, false);

        firstDrain.run();
        secondDrain.run();

        assertEquals(1, ListenerSyncedConfig.MAX_ACTIVE.get());
        assertEquals(
            List.of(new SyncListenerEvent(1, 2, false), new SyncListenerEvent(2, 3, false)),
            ListenerSyncedConfig.EVENTS);
    }

    @Test
    void testRefusesAPayloadThatBreaksTheReceivingRules(@TempDir Path tempDir) {
        RegisteredConfig<WireTypesConfig> source =
            create(WireTypesConfig.class, tempDir.resolve("source"));
        RegisteredConfig<WireTypesConfig> target =
            create(WireTypesConfig.class, tempDir.resolve("target"));

        WireTypesConfig candidate = source.state().copyOfCanonical();
        candidate.mood = null;
        source.state().replace(candidate);
        ConfigBytes payload = SyncedConfig.of(source).cachedSnapshot().data();

        assertThrows(
            LiteConfigException.class,
            () -> SyncedConfig.of(target).prepare(payload));
        assertEquals(
            TranslationKey.SYNC_FAILED,
            receive(SyncedConfig.of(target), payload).disconnectReason());
        assertEquals(Mood.BRIGHT, target.state().published().mood);
    }

    @Test
    void testLeavesFieldsThatDidNotOptInAlone(@TempDir Path tempDir) {
        RegisteredConfig<PartiallySyncedConfig> source =
            create(PartiallySyncedConfig.class, tempDir.resolve("source"));
        RegisteredConfig<PartiallySyncedConfig> target =
            create(PartiallySyncedConfig.class, tempDir.resolve("target"));

        PartiallySyncedConfig candidate = source.state().copyOfCanonical();
        candidate.shared = 99;
        candidate.private_ = "server only";
        source.state().replace(candidate);

        apply(SyncedConfig.of(target), SyncedConfig.of(source).cachedSnapshot().data());

        assertEquals(99, target.state().published().shared);
        assertEquals("local", target.state().published().private_);
    }

    @Test
    void testCarriesNoValuesForAConfigThatKeepsToItself(@TempDir Path tempDir) {
        RegisteredConfig<TestFixtures.SimpleConfig> config =
            create(TestFixtures.SimpleConfig.class, tempDir);
        SyncedConfig<TestFixtures.SimpleConfig> synced = SyncedConfig.of(config);

        String before = config.state().published().text;
        apply(synced, synced.cachedSnapshot().data());

        assertEquals(before, config.state().published().text);
    }

    @Test
    void testRejectsTrailingPayloadBytes(@TempDir Path tempDir) {
        RegisteredConfig<TestFixtures.SyncedConfig> config =
            create(TestFixtures.SyncedConfig.class, tempDir);
        SyncedConfig<TestFixtures.SyncedConfig> synced = SyncedConfig.of(config);

        byte[] encoded = synced.cachedSnapshot().data().bytes();
        byte[] padded = new byte[encoded.length + 1];
        System.arraycopy(encoded, 0, padded, 0, encoded.length);

        assertThrows(
            LiteConfigException.class,
            () -> synced.prepare(ConfigBytes.of(padded)));
        assertEquals(
            TranslationKey.SYNC_FAILED,
            receive(synced, ConfigBytes.of(padded)).disconnectReason());
    }

    @Test
    void staleSchemaRejectsPayloadWithoutReplacingAnExistingOverlay(@TempDir Path tempDir) {
        RegisteredConfig<PartiallySyncedConfig> source =
            create(PartiallySyncedConfig.class, tempDir.resolve("source"));
        RegisteredConfig<PartiallySyncedConfig> target =
            create(PartiallySyncedConfig.class, tempDir.resolve("target"));
        PartiallySyncedConfig candidate = source.state().copyOfCanonical();
        candidate.shared = 9;
        source.state().replace(candidate);
        SyncedConfig<PartiallySyncedConfig> receiver = SyncedConfig.of(target);
        ConfigBytes valid = SyncedConfig.of(source).cachedSnapshot().data();
        apply(receiver, valid);
        byte[] stale = valid.bytes();
        stale[0] ^= 1;

        assertThrows(LiteConfigException.class, () -> receiver.prepare(ConfigBytes.of(stale)));
        assertEquals(TranslationKey.SYNC_FAILED, receive(receiver, ConfigBytes.of(stale)).disconnectReason());
        assertEquals(9, target.state().published().shared);
        assertEquals(1, target.state().copyOfLocal().shared);
        assertTrue(target.state().remoteActive());
    }

    @Test
    void validatesTheEffectiveSnapshotAfterCarryingOverRestartValues(@TempDir Path tempDir) {
        RegisteredConfig<DependentRestartConfig> source =
            create(DependentRestartConfig.class, tempDir.resolve("source"));
        RegisteredConfig<DependentRestartConfig> target =
            create(DependentRestartConfig.class, tempDir.resolve("target"));
        DependentRestartConfig candidate = source.state().copyOfCanonical();
        candidate.poolSize = 32;
        candidate.workers = 16;
        source.state().replace(candidate);
        ConfigBytes payload = SyncedConfig.of(source).cachedSnapshot().data();
        SyncedConfig<DependentRestartConfig> receiver = SyncedConfig.of(target);

        assertThrows(LiteConfigException.class, () -> receiver.prepare(payload));
        assertEquals(TranslationKey.SYNC_FAILED, receive(receiver, payload).disconnectReason());
        assertEquals(8, target.state().published().poolSize);
        assertEquals(4, target.state().published().workers);
        assertTrue(target.state().pending().isEmpty());
        assertFalse(target.state().remoteActive());
    }

    @Test
    void strictRestartDisconnectsOnlyWhenRestartValuesDiffer(@TempDir Path tempDir) {
        RegisteredConfig<StrictRestartConfig> source =
            create(StrictRestartConfig.class, tempDir.resolve("source"));
        RegisteredConfig<StrictRestartConfig> target =
            create(StrictRestartConfig.class, tempDir.resolve("target"));
        SyncedConfig<StrictRestartConfig> receiver = SyncedConfig.of(target);
        StrictRestartConfig candidate = source.state().copyOfCanonical();
        candidate.immediate = 9;
        source.state().replace(candidate);

        ClientSyncResult equal = receive(receiver, SyncedConfig.of(source).cachedSnapshot().data());
        assertTrue(equal.completed());
        assertFalse(equal.restartRequired());
        assertNull(equal.disconnectReason());
        assertEquals(9, target.state().published().immediate);

        candidate = source.state().copyOfCanonical();
        candidate.restartValue = 32;
        source.state().replace(candidate);
        ClientSyncResult different = receive(receiver, SyncedConfig.of(source).cachedSnapshot().data());

        assertTrue(different.completed());
        assertTrue(different.restartRequired());
        assertEquals(TranslationKey.RESTART_REQUIRED_SERVER, different.disconnectReason());
        assertEquals(8, target.state().published().restartValue);
        assertEquals(32, target.state().pending().orElseThrow().restartValue);
        receiver.clearRemote();
        assertEquals(8, target.state().published().restartValue);
        assertEquals(1, target.state().published().immediate);
        assertEquals(32, target.state().pending().orElseThrow().restartValue);
    }

    @Test
    void defaultRestartPolicyDefersWithoutDisconnecting(@TempDir Path tempDir) {
        RegisteredConfig<DependentRestartConfig> source =
            create(DependentRestartConfig.class, tempDir.resolve("source"));
        RegisteredConfig<DependentRestartConfig> target =
            create(DependentRestartConfig.class, tempDir.resolve("target"));
        DependentRestartConfig candidate = source.state().copyOfCanonical();
        candidate.poolSize = 32;
        source.state().replace(candidate);

        ClientSyncResult result = receive(
            SyncedConfig.of(target), SyncedConfig.of(source).cachedSnapshot().data());

        assertTrue(result.completed());
        assertTrue(result.restartRequired());
        assertNull(result.disconnectReason());
        assertEquals(8, target.state().published().poolSize);
        assertEquals(32, target.state().pending().orElseThrow().poolSize);
    }

    @Test
    void restartPolicyParticipatesInTheSchemaFingerprint(@TempDir Path tempDir) throws Exception {
        RegisteredConfig<StrictRestartConfig> config = create(StrictRestartConfig.class, tempDir);
        var schema = SyncedConfig.class.getDeclaredMethod(
            "schemaOf", RegisteredConfig.class, List.class, RestartSyncPolicy.class);
        schema.setAccessible(true);

        long deferred = (long) schema.invoke(null, config, List.of(), RestartSyncPolicy.DEFER);
        long strict = (long) schema.invoke(null, config, List.of(), RestartSyncPolicy.DISCONNECT);

        assertTrue(deferred != strict);
    }

    @Test
    void testRemoteOverlayPreservesTheClientFileAndRestoresLocalPreferences(@TempDir Path tempDir) throws Exception {
        RegisteredConfig<PartiallySyncedConfig> server =
            create(PartiallySyncedConfig.class, tempDir.resolve("server"));

        Path clientDir = tempDir.resolve("client");

        ConfigHolder<PartiallySyncedConfig> client = LiteConfig.holder(PartiallySyncedConfig.class)
            .modId("mod")
            .baseDir(clientDir.toString())
            .create();

        client.updateAndSave(config -> config.private_ = "chosen by the player");
        int localShared = client.data().shared;

        PartiallySyncedConfig fromServer = server.state().copyOfCanonical();
        fromServer.shared = localShared + 7;
        server.state().replace(fromServer);

        ClientSyncResult result = ClientConfigSync.receivePayload(
            new ConfigSyncS2CPacket(
                true,
                Map.of(
                    server.model().syncId(),
                    SyncedConfig.of(server).cachedSnapshot().data()
                )
            )
        );

        assertTrue(result.completed());
        assertNull(result.disconnectReason());

        assertEquals(localShared + 7, client.data().shared);

        String written = Files.readString(clientDir.resolve("mod").resolve("partial-sync.json5"));

        assertTrue(written.contains("chosen by the player"));
        assertTrue(
            written.contains("\"shared\": " + localShared),
            "a remote payload must preserve the local preference file: " + written);

        assertEquals(localShared + 7, client.data().shared);
        assertEquals("chosen by the player", client.data().private_);
        ClientConfigSync.resetClientConnection();
        assertEquals(localShared, client.data().shared);
        assertEquals("chosen by the player", client.data().private_);
    }

    private static void apply(SyncedConfig<?> receiver, ConfigBytes payload) {
        ClientSyncResult result = receive(receiver, payload);
        assertTrue(result.completed());
        assertFalse(result.restartRequired());
        assertNull(result.disconnectReason());
    }

    private static ClientSyncResult receive(SyncedConfig<?> receiver, ConfigBytes payload) {
        Map<String, SyncedConfig<?>> synced = Map.of(receiver.id(), receiver);
        return new ConfigSyncTransactions(synced::get).receive(
            new ConfigSyncS2CPacket(true, Map.of(receiver.id(), payload)));
    }

    private static <T> RegisteredConfig<T> create(Class<T> type, Path tempDir) {
        ConfigSettings<T> settings = new ConfigSettings<>(
            type,
            new ConfigScope("mod"),
            tempDir.toAbsolutePath().normalize()
        );
        return RegisteredConfigs.create(settings);
    }

    private static void registerCustomWireCodec() {
        if (LiteConfig.codecs().find(RgbColor.class).isEmpty()) {
            LiteConfig.codecs()
                .registerCodec(RgbColor.class, RgbColor.CODEC)
                .registerStreamCodec(RgbColor.class, RgbColor.STREAM_CODEC);
        }
    }

    public enum Mood {
        BRIGHT,
        CALM
    }

    @Config(name = "custom-wire", sync = SyncMode.ENABLED)
    public static class CustomWireConfig {
        public RgbColor color = new RgbColor(1, 2);
    }

    @Config(name = "listener-sync", sync = SyncMode.ENABLED)
    public static class ListenerSyncedConfig {

        private static final List<SyncListenerEvent> EVENTS =
            new ArrayList<>();
        private static final List<String> THREADS =
            new ArrayList<>();
        private static final AtomicInteger ACTIVE = new AtomicInteger();
        private static final AtomicInteger MAX_ACTIVE = new AtomicInteger();
        private static Runnable AFTER_RECORD = () -> {
        };

        @Entry(listener = "record")
        public int shared = 1;

        private void record(Integer oldValue, Integer newValue, boolean fromSync) {
            int active = ACTIVE.incrementAndGet();
            MAX_ACTIVE.accumulateAndGet(active, Math::max);
            try {
                EVENTS.add(new SyncListenerEvent(oldValue, newValue, fromSync));
                THREADS.add(Thread.currentThread().getName());
                AFTER_RECORD.run();
            } finally {
                ACTIVE.decrementAndGet();
            }
        }
    }

    private record SyncListenerEvent(int oldValue, int newValue, boolean fromSync) {
    }

    public record RgbColor(int red, int blue) {

        private static final Codec<RgbColor> CODEC = Codec.STRING.xmap(value -> {
            String[] components = value.split(":", -1);
            return new RgbColor(Integer.parseInt(components[0]), Integer.parseInt(components[1]));
        }, value -> value.red() + ":" + value.blue());
        private static final StreamCodec<ByteBuf, RgbColor> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, RgbColor::red,
            ByteBufCodecs.VAR_INT, RgbColor::blue,
            RgbColor::new
        );
    }

    @Config(name = "snapshot-lock", sync = SyncMode.ENABLED)
    public static class SnapshotLockConfig {
        public SnapshotLockValue value = new SnapshotLockValue(1);
    }

    public record SnapshotLockValue(int value) {
        private static Runnable PROBE = () -> {};
        private static final Codec<SnapshotLockValue> CODEC =
            Codec.INT.xmap(SnapshotLockValue::new, SnapshotLockValue::value);
        private static final StreamCodec<ByteBuf, SnapshotLockValue> STREAM_CODEC = StreamCodec.of(
            (buffer, value) -> {
                PROBE.run();
                ByteBufCodecs.INT.encode(buffer, value.value());
            },
            buffer -> new SnapshotLockValue(ByteBufCodecs.INT.decode(buffer))
        );
    }

    public record IntRange(int minimum, int maximum) {

        private static final Codec<IntRange> CODEC = RecordCodecBuilder.create(
            instance -> instance.group(
                Codec.INT.fieldOf("minimum").forGetter(IntRange::minimum),
                Codec.INT.fieldOf("maximum").forGetter(IntRange::maximum)
            ).apply(instance, IntRange::new)
        );

        private static final StreamCodec<ByteBuf, IntRange> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, IntRange::minimum,
            ByteBufCodecs.VAR_INT, IntRange::maximum,
            IntRange::new
        );
    }

    @Config(name = "wire-types", sync = SyncMode.ENABLED)
    public static class WireTypesConfig {
        public boolean flag = true;
        public byte tiny = 1;
        public short small = 2;
        public char letter = 'a';
        public int count = 3;
        public long big = 4L;
        public float ratio = 0.5f;
        public double precise = 6.5d;
        public String label = "start";
        public Mood mood = Mood.BRIGHT;
    }

    @Config(name = "vanilla-codec", sync = SyncMode.ENABLED)
    public static class VanillaCodecConfig {
        public IntRange range = new IntRange(2, 8);
    }

    @Config(name = "collection-wire", sync = SyncMode.ENABLED)
    public static class CollectionWireConfig {
        public List<String> labels = new ArrayList<>();
        public Map<String, Integer> weights = new LinkedHashMap<>();
        public Map<String, List<RgbColor>> colors = new LinkedHashMap<>();
        public Set<String> tags = new LinkedHashSet<>();
    }

    @Config(name = "partial-sync")
    public static class PartiallySyncedConfig {

        @Entry(sync = SyncMode.ENABLED)
        public int shared = 1;

        public String private_ = "local";
    }

    @Config(name = "dependent-restart", sync = SyncMode.ENABLED)
    public static class DependentRestartConfig implements ConfigExtension {
        @Entry(restart = true)
        public int poolSize = 8;
        public int workers = 4;

        @Override
        public void validate(List<Violation> violations) {
            if (workers > poolSize) {
                violations.add(Violation.of("workers.capacity", "Workers exceed the active pool capacity"));
            }
        }
    }

    @Config(name = "strict-restart", sync = SyncMode.ENABLED, restartSyncPolicy = RestartSyncPolicy.DISCONNECT)
    public static class StrictRestartConfig {
        @Entry(restart = true)
        public int restartValue = 8;
        public int immediate = 1;
    }

}
