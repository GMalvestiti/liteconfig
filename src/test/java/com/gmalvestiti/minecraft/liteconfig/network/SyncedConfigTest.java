package com.gmalvestiti.minecraft.liteconfig.network;

import com.gmalvestiti.minecraft.liteconfig.api.ConfigHolder;
import com.gmalvestiti.minecraft.liteconfig.api.LiteConfig;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Config;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Entry;
import com.gmalvestiti.minecraft.liteconfig.context.ConfigSettings;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigScope;
import com.gmalvestiti.minecraft.liteconfig.exception.LiteConfigException;
import com.gmalvestiti.minecraft.liteconfig.network.packet.ConfigSyncS2CPacket;
import com.gmalvestiti.minecraft.liteconfig.registry.RegisteredConfig;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(ConfigRegistryIsolation.class)
class SyncedConfigTest {

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

        SyncedConfig.of(target).apply(SyncedConfig.of(source).snapshot().data());

        assertEquals(9, target.state().published().maxTeamSize);
        assertEquals(candidate.strictMode, target.state().published().strictMode);
        assertEquals(candidate.section.strict, target.state().published().section.strict);
    }

    @Test
    void testCarriesEveryLeafTypeItSupports(@TempDir Path tempDir) {
        RegisteredConfig<WireTypesConfig> source = create(WireTypesConfig.class, tempDir.resolve("source"));
        RegisteredConfig<WireTypesConfig> target = create(WireTypesConfig.class, tempDir.resolve("target"));

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

        SyncedConfig.of(target).apply(SyncedConfig.of(source).snapshot().data());

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
        RegisteredConfig<WireTypesConfig> source = create(WireTypesConfig.class, tempDir.resolve("source"));
        RegisteredConfig<WireTypesConfig> target = create(WireTypesConfig.class, tempDir.resolve("target"));

        WireTypesConfig candidate = source.state().copyOfCanonical();
        candidate.label = null;
        source.state().replace(candidate);

        SyncedConfig.of(target).apply(SyncedConfig.of(source).snapshot().data());

        assertNull(target.state().published().label);
    }

    @Test
    void testCarriesARegisteredCustomValue(@TempDir Path tempDir) {
        registerCustomWireCodec();
        RegisteredConfig<CustomWireConfig> source = create(CustomWireConfig.class, tempDir.resolve("source"));
        RegisteredConfig<CustomWireConfig> target = create(CustomWireConfig.class, tempDir.resolve("target"));

        CustomWireConfig candidate = source.state().copyOfCanonical();
        candidate.color = new RgbColor(7, 8);
        source.state().replace(candidate);

        SyncedConfig.of(target).apply(SyncedConfig.of(source).snapshot().data());

        assertEquals(new RgbColor(7, 8), target.state().published().color);
    }

    @Test
    void testCarriesAValueRegisteredWithVanillaCodecs(@TempDir Path tempDir) {
        if (LiteConfig.codecs().find(IntRange.class).isEmpty()) {
            LiteConfig.codecs()
                .registerCodec(IntRange.class, IntRange.CODEC)
                .registerStreamCodec(IntRange.class, IntRange.STREAM_CODEC);
        }
        RegisteredConfig<VanillaCodecConfig> source = create(VanillaCodecConfig.class, tempDir.resolve("source"));
        RegisteredConfig<VanillaCodecConfig> target = create(VanillaCodecConfig.class, tempDir.resolve("target"));

        VanillaCodecConfig candidate = source.state().copyOfCanonical();
        candidate.range = new IntRange(4, 20);
        source.state().replace(candidate);

        SyncedConfig.of(target).apply(SyncedConfig.of(source).snapshot().data());

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

        SyncedConfig.of(target).apply(SyncedConfig.of(source).snapshot().data());

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
        ConfigBytes firstPayload = SyncedConfig.of(config).snapshot().data();

        CollectionWireConfig second = config.state().copyOfCanonical();
        second.tags = new LinkedHashSet<>(List.of("second", "first"));
        config.state().replace(second);
        ConfigBytes secondPayload = SyncedConfig.of(config).snapshot().data();

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

        SyncedConfig.of(target).apply(SyncedConfig.of(source).snapshot().data());

        assertEquals(candidate.labels, target.state().published().labels);
    }

    @Test
    void testInvokesCallbacksAndListenersOnlyForChangedSyncValues(@TempDir Path tempDir) {
        CallbackSyncedConfig.EVENTS.clear();
        RegisteredConfig<CallbackSyncedConfig> source =
            create(CallbackSyncedConfig.class, tempDir.resolve("source"));
        RegisteredConfig<CallbackSyncedConfig> target =
            create(CallbackSyncedConfig.class, tempDir.resolve("target"));
        int[] updates = {0};

        CallbackSyncedConfig candidate = source.state().copyOfCanonical();
        candidate.shared = 6;
        source.state().replace(candidate);
        ConfigBytes payload = SyncedConfig.of(source).snapshot().data();
        target.notifier().addUpdateListener(state -> updates[0]++);

        SyncedConfig.of(target).apply(payload);
        SyncedConfig.of(target).apply(payload);

        assertEquals(List.of(new SyncCallbackEvent(1, 6, true)), CallbackSyncedConfig.EVENTS);
        assertEquals(1, updates[0]);
    }

    @Test
    void testPersistsAndDispatchesCallbacksOnTheConfigWorker(
        @TempDir Path tempDir
    ) throws Exception {
        CallbackSyncedConfig.EVENTS.clear();
        CallbackSyncedConfig.THREADS.clear();
        RegisteredConfig<CallbackSyncedConfig> source =
            create(CallbackSyncedConfig.class, tempDir.resolve("source"));
        RegisteredConfig<CallbackSyncedConfig> target =
            create(CallbackSyncedConfig.class, tempDir.resolve("target"));
        ConfigSyncRegistry.register(target);
        CallbackSyncedConfig candidate = source.state().copyOfCanonical();
        candidate.shared = 8;
        source.state().replace(candidate);

        ExecutorService clientThread = Executors.newSingleThreadExecutor(
            task -> new Thread(task, "test-client-main"));
        try {
            ConfigSyncRegistry.setClientMainThreadExecutor(clientThread);
            ConfigSyncRegistry.receiveResultAsync(new ConfigSyncS2CPacket(
                true, Map.of(
                    target.model().syncId(), SyncedConfig.of(source).snapshot().data()))).join();
            clientThread.submit(() -> {}).get();
        } finally {
            clientThread.shutdownNow();
        }

        assertTrue(CallbackSyncedConfig.THREADS.getFirst().startsWith("liteconfig-io"));
        assertTrue(Files.readString(tempDir.resolve("target").resolve("callback-sync.json5"))
            .contains("\"shared\": 8"));
    }

    @Test
    void testListenersReceiveEachPublishedTransition(@TempDir Path tempDir) {
        RegisteredConfig<CallbackSyncedConfig> source =
            create(CallbackSyncedConfig.class, tempDir.resolve("source"));
        RegisteredConfig<CallbackSyncedConfig> target =
            create(CallbackSyncedConfig.class, tempDir.resolve("target"));
        List<Integer> published = new ArrayList<>();
        target.notifier().addUpdateListener(state -> published.add(state.shared));

        CallbackSyncedConfig second = source.state().copyOfCanonical();
        second.shared = 2;
        source.state().replace(second);
        SyncedConfig.of(target).apply(SyncedConfig.of(source).snapshot().data());

        CallbackSyncedConfig third = source.state().copyOfCanonical();
        third.shared = 3;
        source.state().replace(third);
        SyncedConfig.of(target).apply(SyncedConfig.of(source).snapshot().data());

        assertEquals(List.of(2, 3), published);
    }

    @Test
    void testSerializesCallbacksInTransitionOrder(@TempDir Path tempDir) {
        CallbackSyncedConfig.EVENTS.clear();
        CallbackSyncedConfig.ACTIVE.set(0);
        CallbackSyncedConfig.MAX_ACTIVE.set(0);
        RegisteredConfig<CallbackSyncedConfig> config =
            create(CallbackSyncedConfig.class, tempDir);
        CallbackSyncedConfig first = new CallbackSyncedConfig();
        CallbackSyncedConfig second = new CallbackSyncedConfig();
        CallbackSyncedConfig third = new CallbackSyncedConfig();
        second.shared = 2;
        third.shared = 3;
        Runnable firstDrain = config.model().callbacks().enqueueChanged(first, second, false);
        Runnable secondDrain = config.model().callbacks().enqueueChanged(second, third, false);

        CompletableFuture.allOf(
            CompletableFuture.runAsync(firstDrain),
            CompletableFuture.runAsync(secondDrain)).join();

        assertEquals(1, CallbackSyncedConfig.MAX_ACTIVE.get());
        assertEquals(
            List.of(new SyncCallbackEvent(1, 2, false), new SyncCallbackEvent(2, 3, false)),
            CallbackSyncedConfig.EVENTS);
    }

    @Test
    void testRefusesAPayloadThatBreaksTheReceivingRules(@TempDir Path tempDir) {
        RegisteredConfig<WireTypesConfig> source = create(WireTypesConfig.class, tempDir.resolve("source"));
        RegisteredConfig<WireTypesConfig> target = create(WireTypesConfig.class, tempDir.resolve("target"));

        WireTypesConfig candidate = source.state().copyOfCanonical();
        candidate.mood = null;
        source.state().replace(candidate);
        ConfigBytes payload = SyncedConfig.of(source).snapshot().data();

        assertThrows(LiteConfigException.class, () -> SyncedConfig.of(target).apply(payload));
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

        SyncedConfig.of(target).apply(SyncedConfig.of(source).snapshot().data());

        assertEquals(99, target.state().published().shared);
        assertEquals("local", target.state().published().private_);
    }

    @Test
    void testCarriesNoValuesForAConfigThatKeepsToItself(@TempDir Path tempDir) {
        RegisteredConfig<TestFixtures.SimpleConfig> config = create(TestFixtures.SimpleConfig.class, tempDir);
        SyncedConfig<TestFixtures.SimpleConfig> synced = SyncedConfig.of(config);

        String before = config.state().published().text;
        synced.apply(synced.snapshot().data());

        assertEquals(before, config.state().published().text);
    }

    @Test
    void testRejectsTrailingPayloadBytes(@TempDir Path tempDir) {
        RegisteredConfig<TestFixtures.SyncedConfig> config = create(TestFixtures.SyncedConfig.class, tempDir);
        SyncedConfig<TestFixtures.SyncedConfig> synced = SyncedConfig.of(config);

        byte[] encoded = synced.snapshot().data().bytes();
        byte[] padded = new byte[encoded.length + 1];
        System.arraycopy(encoded, 0, padded, 0, encoded.length);

        assertThrows(LiteConfigException.class, () -> synced.apply(ConfigBytes.of(padded)));
    }

    @Test
    void testDurablyAppliesServerValuesToTheClientFile(@TempDir Path tempDir) throws Exception {
        RegisteredConfig<PartiallySyncedConfig> server =
            create(PartiallySyncedConfig.class, tempDir.resolve("server"));

        ConfigSyncRegistry.initialize();
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

        ConfigSyncRegistry.apply(
            server.model().syncId(), SyncedConfig.of(server).snapshot().data());

        assertEquals(localShared + 7, client.data().shared);

        String written = Files.readString(clientDir.resolve("partial-sync.json5"));

        assertTrue(written.contains("chosen by the player"));
        assertTrue(
            written.contains("\"shared\": " + (localShared + 7)),
            "a durable server payload must write its synced value: " + written);

        assertEquals(localShared + 7, client.data().shared);
        assertEquals("chosen by the player", client.data().private_);
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

    @Config(name = "custom-wire", sync = true)
    public static class CustomWireConfig {
        public RgbColor color = new RgbColor(1, 2);
    }

    @Config(name = "callback-sync", sync = true)
    public static class CallbackSyncedConfig {

        private static final List<SyncCallbackEvent> EVENTS =
            Collections.synchronizedList(new ArrayList<>());
        private static final List<String> THREADS =
            Collections.synchronizedList(new ArrayList<>());
        private static final AtomicInteger ACTIVE = new AtomicInteger();
        private static final AtomicInteger MAX_ACTIVE = new AtomicInteger();

        @Entry(callback = "record")
        public int shared = 1;

        private void record(Integer oldValue, Integer newValue, boolean fromSync) {
            int active = ACTIVE.incrementAndGet();
            MAX_ACTIVE.accumulateAndGet(active, Math::max);
            try {
                Thread.sleep(20);
                EVENTS.add(new SyncCallbackEvent(oldValue, newValue, fromSync));
                THREADS.add(Thread.currentThread().getName());
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(failure);
            } finally {
                ACTIVE.decrementAndGet();
            }
        }
    }

    private record SyncCallbackEvent(int oldValue, int newValue, boolean fromSync) {}

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

    public record IntRange(int minimum, int maximum) {

        private static final Codec<IntRange> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("minimum").forGetter(IntRange::minimum),
            Codec.INT.fieldOf("maximum").forGetter(IntRange::maximum)
        ).apply(instance, IntRange::new));
        private static final StreamCodec<ByteBuf, IntRange> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, IntRange::minimum,
            ByteBufCodecs.VAR_INT, IntRange::maximum,
            IntRange::new
        );
    }

    @Config(name = "wire-types", sync = true)
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

    @Config(name = "vanilla-codec", sync = true)
    public static class VanillaCodecConfig {
        public IntRange range = new IntRange(2, 8);
    }

    @Config(name = "collection-wire", sync = true)
    public static class CollectionWireConfig {
        public List<String> labels = new ArrayList<>();
        public Map<String, Integer> weights = new LinkedHashMap<>();
        public Map<String, List<RgbColor>> colors = new LinkedHashMap<>();
        public Set<String> tags = new LinkedHashSet<>();
    }

    @Config(name = "partial-sync")
    public static class PartiallySyncedConfig {

        @Entry(sync = true)
        public int shared = 1;

        public String private_ = "local";
    }

}
