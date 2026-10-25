package com.gmalvestiti.minecraft.liteconfig.network;

import com.gmalvestiti.minecraft.liteconfig.api.ConfigHolder;
import com.gmalvestiti.minecraft.liteconfig.api.ConfigType;
import com.gmalvestiti.minecraft.liteconfig.api.FailurePolicy;
import com.gmalvestiti.minecraft.liteconfig.api.LiteConfig;
import com.gmalvestiti.minecraft.liteconfig.api.SyncMode;
import com.gmalvestiti.minecraft.liteconfig.api.UpdateResult;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Config;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Entry;
import com.gmalvestiti.minecraft.liteconfig.context.ConfigSettings;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigError;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigScope;
import com.gmalvestiti.minecraft.liteconfig.exception.LiteConfigException;
import com.gmalvestiti.minecraft.liteconfig.network.packet.ConfigSyncRequestC2SPacket;
import com.gmalvestiti.minecraft.liteconfig.network.packet.ConfigSyncS2CPacket;
import com.gmalvestiti.minecraft.liteconfig.registry.ConfigRegistry;
import com.gmalvestiti.minecraft.liteconfig.registry.RegisteredConfig;
import com.gmalvestiti.minecraft.liteconfig.support.ConfigRegistryIsolation;
import com.gmalvestiti.minecraft.liteconfig.support.RegisteredConfigs;
import com.gmalvestiti.minecraft.liteconfig.support.TestSyncPlatform;
import com.mojang.serialization.Codec;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.EncoderException;
import org.junit.jupiter.api.AfterEach;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.VarInt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(ConfigRegistryIsolation.class)
class ConfigSyncLimitsTest {

    private final List<ConfigHolder<?>> reservations = new ArrayList<>();

    @AfterEach
    void closeReservations() {
        reservations.forEach(ConfigHolder::close);
    }

    @BeforeEach
    void codecs() {
        if (LiteConfig.codecs().find(SizedValue.class).isEmpty()) {
            LiteConfig.codecs()
                .registerCodec(SizedValue.class, Codec.INT.xmap(SizedValue::new, SizedValue::size))
                .registerStreamCodec(SizedValue.class, StreamCodec.of(
                    (ByteBuf buffer, SizedValue value) -> {
                        assertTrue(ConfigSyncRegistry.runtimeAvailable(), "Stream codec used before game initialization");
                        if (value.size() == -2) {
                            throw new EncoderException("Broken custom encoder");
                        }
                        buffer.writeZero(value.size());
                    },
                    buffer -> {
                        int size = buffer.readableBytes();
                        buffer.skipBytes(size);
                        return new SizedValue(size);
                    }));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void fallbackRejectsOversizeBeforePublishingSavingOrNotifying(boolean save, @TempDir Path directory)
        throws Exception {
        try (ConfigHolder<PayloadConfig> holder = holder(PayloadConfig.class, directory)) {
            String beforeFile = Files.readString(holder.path().orElseThrow());
            PayloadConfig before = holder.data();
            AtomicInteger events = new AtomicInteger();
            holder.onUpdate(ignored -> events.incrementAndGet());
            holder.onSave(ignored -> events.incrementAndGet());
            List<ConfigSyncS2CPacket> broadcasts = new ArrayList<>();
            TestSyncPlatform.setBroadcastScheduler(broadcasts::addAll);
            UpdateResult result = save
                ? holder.updateAndSave(value -> value.first = new SizedValue(ConfigSyncProtocol.MAX_CONFIG_BYTES))
                : holder.update(value -> value.first = new SizedValue(ConfigSyncProtocol.MAX_CONFIG_BYTES));

            assertFalse(result.accepted());
            assertEquals("sync.value-size", result.violations().getFirst().id());
            assertTrue(result.violations().getFirst().message().contains("maximum"));
            assertSame(before, holder.data());
            assertTrue(holder.pending().isEmpty());
            assertEquals(beforeFile, Files.readString(holder.path().orElseThrow()));
            assertEquals(0, events.get());
            assertTrue(broadcasts.isEmpty());
            assertEquals(1, ServerConfigSync.beginHandshake(true).size());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void strictRejectsBeforePublicationAndPersistence(boolean save, @TempDir Path directory) throws Exception {
        try (ConfigHolder<StrictPayloadConfig> holder = holder(StrictPayloadConfig.class, directory)) {
            StrictPayloadConfig before = holder.data();
            String beforeFile = Files.readString(holder.path().orElseThrow());
            LiteConfigException failure = assertThrows(LiteConfigException.class, () -> {
                if (save) {
                    holder.updateAndSave(value -> value.first = new SizedValue(ConfigSyncProtocol.MAX_CONFIG_BYTES));
                } else {
                    holder.update(value -> value.first = new SizedValue(ConfigSyncProtocol.MAX_CONFIG_BYTES));
                }
            });
            assertEquals(ConfigError.VALIDATION_FAILED, failure.error());
            assertSame(before, holder.data());
            assertEquals(beforeFile, Files.readString(holder.path().orElseThrow()));
        }
    }

    @Test
    void acceptsExactConfigLimitAndRejectsOneByteMore(@TempDir Path directory) {
        try (ConfigHolder<PayloadConfig> holder = holder(PayloadConfig.class, directory)) {
            // Schema (8), two nullable markers (2), and length prefixes (3 + 1).
            int exactSize = ConfigSyncProtocol.MAX_CONFIG_BYTES - 14;
            assertTrue(holder.update(value -> value.first = new SizedValue(exactSize)).accepted());
            String id = ServerConfigSync.beginHandshake(true).getFirst().hashes().keySet().iterator().next();
            ConfigSyncS2CPacket packet = ServerConfigSync.payloadsFor(
                new ConfigSyncRequestC2SPacket(true, Set.of(id))).getFirst();
            assertEquals(ConfigSyncProtocol.MAX_CONFIG_BYTES, packet.configs().get(id).size());

            PayloadConfig before = holder.data();
            UpdateResult result = holder.update(value -> value.first = new SizedValue(exactSize + 1));
            assertFalse(result.accepted());
            assertEquals("sync.config-size", result.violations().getFirst().id());
            assertTrue(result.violations().getFirst().message().contains(
                Integer.toString(ConfigSyncProtocol.MAX_CONFIG_BYTES + 1)));
            assertSame(before, holder.data());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 126, 127, 128, 16_382, 16_383, 16_384, 995_890})
    void validationAndEncodingAgreeAcrossLengthPrefixes(int size, @TempDir Path directory) {
        try (ConfigHolder<PayloadConfig> holder = holder(PayloadConfig.class, directory)) {
            assertTrue(holder.update(value -> value.first = new SizedValue(size)).accepted());
            SyncedConfig<?> synced = ConfigSyncRegistry.get("mod:" + PayloadConfig.class.getName());
            int expected = Long.BYTES + VarInt.getByteSize(size + 1) + size + 1 + 2;
            assertEquals(expected, synced.cachedSnapshot().data().size());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"list", "map", "set", "array", "text"})
    void builtInLimitsRespectUpdatePolicies(String field, @TempDir Path directory) throws Exception {
        try (ConfigHolder<CollectionConfig> fallback = holder(CollectionConfig.class, directory);
             ConfigHolder<StrictCollectionConfig> strict = holder(StrictCollectionConfig.class, directory)) {
            CollectionConfig before = fallback.data();
            String file = Files.readString(fallback.path().orElseThrow());
            AtomicInteger events = new AtomicInteger();
            fallback.onUpdate(ignored -> events.incrementAndGet());
            fallback.onSave(ignored -> events.incrementAndGet());
            UpdateResult result = fallback.updateAndSave(value -> exceedBuiltInLimit(value, field));
            assertFalse(result.accepted());
            assertEquals(field.equals("text") ? "sync.string-size" : "sync.collection-size",
                result.violations().getFirst().id());
            assertTrue(result.violations().getFirst().message().contains(field));
            assertTrue(result.violations().getFirst().message().contains("mod:" + CollectionConfig.class.getName()));
            assertSame(before, fallback.data());
            assertTrue(fallback.pending().isEmpty());
            assertEquals(file, Files.readString(fallback.path().orElseThrow()));
            assertEquals(0, events.get());

            StrictCollectionConfig strictBefore = strict.data();
            LiteConfigException failure = assertThrows(LiteConfigException.class,
                () -> strict.updateAndSave(value -> exceedBuiltInLimit(value, field)));
            assertEquals(ConfigError.VALIDATION_FAILED, failure.error());
            assertSame(strictBefore, strict.data());
        }
    }

    @Test
    void builtInLimitInInitialFileUsesReadFallback(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("mod").resolve("collections.json5");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"list\":[" + "1,".repeat(16_384) + "1]}");
        try (ConfigHolder<CollectionConfig> holder = holder(CollectionConfig.class, directory)) {
            assertTrue(holder.data().list.isEmpty());
            assertTrue(Files.size(file) < 1_000);
            assertEquals(1, manifestSize());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, -2})
    void unexpectedCodecDefectsStillThrowUnderFallback(int invalid, @TempDir Path directory) {
        try (ConfigHolder<PayloadConfig> holder = holder(PayloadConfig.class, directory)) {
            PayloadConfig before = holder.data();
            LiteConfigException failure = assertThrows(LiteConfigException.class,
                () -> holder.update(value -> value.first = new SizedValue(invalid)));
            assertEquals(ConfigError.UNEXPECTED_FAILURE, failure.error());
            assertSame(before, holder.data());
        }
    }

    @Test
    void rejectsCombinedValuesAndPendingRestartSelections(@TempDir Path directory) {
        try (ConfigHolder<PayloadConfig> holder = holder(PayloadConfig.class, directory)) {
            UpdateResult combined = holder.update(value -> {
                value.first = new SizedValue(ConfigSyncProtocol.MAX_CONFIG_BYTES / 2);
                value.second = new SizedValue(ConfigSyncProtocol.MAX_CONFIG_BYTES / 2);
            });
            assertFalse(combined.accepted());
            assertEquals("sync.config-size", combined.violations().getFirst().id());
            assertEquals(0, holder.data().first.size());
            assertTrue(holder.pending().isEmpty());

            assertTrue(holder.update(value -> value.second = new SizedValue(100)).accepted());
            UpdateResult pending = holder.update(value ->
                value.second = new SizedValue(ConfigSyncProtocol.MAX_CONFIG_BYTES));
            assertFalse(pending.accepted());
            assertEquals(100, holder.pending().orElseThrow().second.size());
        }
    }

    @Test
    void defersSizeValidationUntilRuntimeAndChecksWithoutRecipients(@TempDir Path directory) throws Exception {
        ConfigRegistryIsolation.beforeGameStartup();
        TestSyncPlatform.setRemoteConnectionCheck(() -> {
            throw new AssertionError("Early validation must not inspect a connection");
        });
        try (ConfigHolder<PayloadConfig> holder = holder(PayloadConfig.class, directory)) {
            assertTrue(holder.updateAndSave(value ->
                value.first = new SizedValue(ConfigSyncProtocol.MAX_CONFIG_BYTES)).accepted());
            assertTrue(ServerConfigSync.beginHandshake(true).isEmpty());
            holder.load();
            assertEquals(ConfigSyncProtocol.MAX_CONFIG_BYTES, holder.data().first.size());
            assertTrue(holder.updateAndSave(value -> value.first = new SizedValue(0)).accepted());
            TestSyncPlatform.reset();
            TestSyncPlatform.setSyncRecipientsAvailable(false);
            assertFalse(holder.update(value ->
                value.first = new SizedValue(ConfigSyncProtocol.MAX_CONFIG_BYTES)).accepted());
            assertEquals(1, ServerConfigSync.beginHandshake(true).size());
        }
    }

    @Test
    void activationRejectsAnOversizedEarlyPendingSelection(@TempDir Path directory) throws Exception {
        ConfigRegistryIsolation.beforeGameStartup();
        try (ConfigHolder<PayloadConfig> holder = holder(PayloadConfig.class, directory)) {
            assertTrue(holder.update(value ->
                value.second = new SizedValue(ConfigSyncProtocol.MAX_CONFIG_BYTES)).accepted());
            assertTrue(holder.pending().isPresent());
            LiteConfigException failure = assertThrows(LiteConfigException.class, TestSyncPlatform::reset);
            assertEquals(ConfigError.VALIDATION_FAILED, failure.error());
            assertTrue(ServerConfigSync.beginHandshake(true).isEmpty());
            assertTrue(ConfigSyncRegistry.localValues().isEmpty());
        }
    }

    @Test
    void activationRejectsOversizedEarlyDefaults(@TempDir Path directory) throws Exception {
        ConfigRegistryIsolation.beforeGameStartup();
        try (ConfigHolder<OversizedDefaults> holder = holder(OversizedDefaults.class, directory)) {
            assertEquals(ConfigSyncProtocol.MAX_CONFIG_BYTES, holder.data().first.size());
            LiteConfigException failure = assertThrows(LiteConfigException.class, TestSyncPlatform::reset);
            assertEquals(ConfigError.VALIDATION_FAILED, failure.error());
            assertTrue(ServerConfigSync.beginHandshake(true).isEmpty());
            assertTrue(ConfigSyncRegistry.localValues().isEmpty());
        }
    }

    @Test
    void oversizedInitialFileFallsBackBeforeAdvertising(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("mod").resolve("payload.json5");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"first\":" + ConfigSyncProtocol.MAX_CONFIG_BYTES + ",\"second\":0}");
        try (ConfigHolder<PayloadConfig> holder = holder(PayloadConfig.class, directory)) {
            assertEquals(0, holder.data().first.size());
            assertEquals(1, ServerConfigSync.beginHandshake(true).size());
            assertFalse(Files.readString(file).contains(Integer.toString(ConfigSyncProtocol.MAX_CONFIG_BYTES)));
        }
    }

    @Test
    void invalidDefaultsFailBeforeCreatingAFile(@TempDir Path directory) {
        LiteConfigException failure = assertThrows(LiteConfigException.class,
            () -> holder(OversizedDefaults.class, directory).close());
        assertEquals(ConfigError.VALIDATION_FAILED, failure.error());
        assertFalse(Files.exists(directory.resolve("mod").resolve("oversized-defaults.json5")));
        assertTrue(ConfigSyncRegistry.values().isEmpty());
    }

    @Test
    void validatesEffectiveStateAfterRestartCarryOver(@TempDir Path directory) {
        try (ConfigHolder<LargeRestartDefaults> holder = holder(LargeRestartDefaults.class, directory)) {
            LargeRestartDefaults before = holder.data();
            UpdateResult result = holder.update(value -> {
                value.first = new SizedValue(ConfigSyncProtocol.MAX_CONFIG_BYTES / 2);
                value.second = new SizedValue(0);
            });
            assertFalse(result.accepted());
            assertEquals("sync.config-size", result.violations().getFirst().id());
            assertSame(before, holder.data());
            assertTrue(holder.pending().isEmpty());
        }
    }

    @Test
    void strictExplicitLoadDoesNotPublishOversizedFile(@TempDir Path directory) throws Exception {
        try (ConfigHolder<StrictPayloadConfig> holder = holder(StrictPayloadConfig.class, directory)) {
            StrictPayloadConfig before = holder.data();
            String invalid = "{\"first\":" + ConfigSyncProtocol.MAX_CONFIG_BYTES + ",\"second\":0}";
            Files.writeString(holder.path().orElseThrow(), invalid);
            LiteConfigException failure = assertThrows(LiteConfigException.class, holder::load);
            assertEquals(ConfigError.VALIDATION_FAILED, failure.error());
            assertSame(before, holder.data());
            assertEquals(invalid, Files.readString(holder.path().orElseThrow()));
            assertEquals(1, ServerConfigSync.beginHandshake(true).size());
        }
    }

    @Test
    void clientAndUnsyncedFieldsDoNotHaveWireSizeLimits(@TempDir Path directory) {
        try (ConfigHolder<ClientPayloadConfig> client = holder(ClientPayloadConfig.class, directory);
             ConfigHolder<LocalPayloadConfig> local = holder(LocalPayloadConfig.class, directory)) {
            assertTrue(client.update(value ->
                value.first = new SizedValue(ConfigSyncProtocol.MAX_CONFIG_BYTES * 2)).accepted());
            assertTrue(local.update(value ->
                value.first = new SizedValue(ConfigSyncProtocol.MAX_CONFIG_BYTES * 2)).accepted());
            assertTrue(ConfigSyncRegistry.localValues().isEmpty());
        }
    }

    @Test
    void rejectsOverflowRegistrationAndReleasesCapacityOnClose(@TempDir Path directory) {
        reserveSlots(ConfigSyncProtocol.MAX_MANIFEST_ENTRIES - 1, directory);
        ConfigHolder<PayloadConfig> first = holder(PayloadConfig.class, directory);
        assertEquals(ConfigSyncProtocol.MAX_MANIFEST_ENTRIES, ConfigSyncRegistry.localValues().size());
        LiteConfigException failure = assertThrows(LiteConfigException.class,
            () -> holder(StrictPayloadConfig.class, directory).close());
        assertEquals(ConfigError.SYNC_MANIFEST_FULL, failure.error());
        assertTrue(failure.getMessage().contains("8193"));
        assertNull(ConfigSyncRegistry.get("mod:" + StrictPayloadConfig.class.getName()));
        assertEquals(ConfigSyncProtocol.MAX_MANIFEST_ENTRIES, manifestSize());
        first.close();
        try (ConfigHolder<StrictPayloadConfig> replacement = holder(StrictPayloadConfig.class, directory)) {
            assertTrue(replacement.isActive());
            assertEquals(ConfigSyncProtocol.MAX_MANIFEST_ENTRIES, ConfigSyncRegistry.localValues().size());
        }
    }

    @Test
    void worldOnlyConsumesCapacityWhileLocallyActive(@TempDir Path directory) {
        reserveSlots(ConfigSyncProtocol.MAX_MANIFEST_ENTRIES, directory);
        try (ConfigHolder<WorldPayloadConfig> world = holder(WorldPayloadConfig.class, directory);
             ConfigHolder<ClientPayloadConfig> client = holder(ClientPayloadConfig.class, directory)) {
            assertFalse(world.isActive());
            assertEquals(ConfigType.CLIENT, client.type());
            assertEquals(ConfigSyncProtocol.MAX_MANIFEST_ENTRIES, ConfigSyncRegistry.localValues().size());
            LiteConfigException failure = assertThrows(LiteConfigException.class,
                () -> ConfigRegistry.activateWorld(directory.resolve("world")));
            assertEquals(ConfigError.SYNC_MANIFEST_FULL, failure.error());
            assertFalse(world.isActive());
            assertTrue(world.path().isEmpty());
            assertEquals(ConfigSyncProtocol.MAX_MANIFEST_ENTRIES, manifestSize());

            reservations.getFirst().close();
            ConfigRegistry.activateWorld(directory.resolve("world"));
            assertTrue(world.isActive());
            assertEquals(ConfigSyncProtocol.MAX_MANIFEST_ENTRIES, ConfigSyncRegistry.localValues().size());
            assertEquals(ConfigSyncProtocol.MAX_MANIFEST_ENTRIES, manifestSize());
            assertTrue(ServerConfigSync.beginHandshake(true).stream()
                .anyMatch(packet -> packet.hashes().containsKey("mod:" + WorldPayloadConfig.class.getName())));
            ConfigRegistry.deactivateWorld();
            assertEquals(ConfigSyncProtocol.MAX_MANIFEST_ENTRIES - 1, ConfigSyncRegistry.localValues().size());
            assertEquals(ConfigSyncProtocol.MAX_MANIFEST_ENTRIES - 1, manifestSize());
            assertTrue(ServerConfigSync.beginHandshake(true).stream()
                .noneMatch(packet -> packet.hashes().containsKey("mod:" + WorldPayloadConfig.class.getName())));
        }
    }

    @Test
    void concurrentRegistrationsCannotBothTakeTheLastSlot(@TempDir Path directory) throws Exception {
        ConfigRegistryIsolation.beforeGameStartup();
        reserveSlots(ConfigSyncProtocol.MAX_MANIFEST_ENTRIES - 1, directory);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<ConfigHolder<?>> holders = new ConcurrentLinkedQueue<>();
        List<Thread> threads = List.of(
            registrationThread(PayloadConfig.class, directory, start, accepted, failures, holders),
            registrationThread(StrictPayloadConfig.class, directory, start, accepted, failures, holders));
        threads.forEach(Thread::start);
        start.countDown();
        for (Thread thread : threads) {
            thread.join(10_000);
            assertFalse(thread.isAlive());
        }
        assertEquals(1, accepted.get());
        assertEquals(1, failures.size());
        assertEquals(ConfigError.SYNC_MANIFEST_FULL,
            assertInstanceOf(LiteConfigException.class, failures.element()).error());
        assertEquals(ConfigSyncProtocol.MAX_MANIFEST_ENTRIES, ConfigSyncRegistry.localValues().size());
        holders.forEach(ConfigHolder::close);
        assertEquals(ConfigSyncProtocol.MAX_MANIFEST_ENTRIES - 1, ConfigSyncRegistry.localValues().size());
    }

    @Test
    void failedWorldLoadReleasesItsAdmittedSlot(@TempDir Path directory) throws Exception {
        reserveSlots(ConfigSyncProtocol.MAX_MANIFEST_ENTRIES - 1, directory);
        Path worldRoot = directory.resolve("world");
        Path file = worldRoot.resolve("liteconfig").resolve("mod").resolve("strict-world-payload.json5");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"first\":" + ConfigSyncProtocol.MAX_CONFIG_BYTES + ",\"second\":0}");
        try (ConfigHolder<StrictWorldPayloadConfig> world = holder(StrictWorldPayloadConfig.class, directory)) {
            LiteConfigException failure = assertThrows(LiteConfigException.class,
                () -> ConfigRegistry.activateWorld(worldRoot));
            assertEquals(ConfigError.VALIDATION_FAILED, failure.error());
            assertFalse(world.isActive());
            assertEquals(0, world.data().first.size());
            assertTrue(world.path().isEmpty());
            assertEquals(ConfigSyncProtocol.MAX_MANIFEST_ENTRIES - 1, ConfigSyncRegistry.localValues().size());
        }
    }

    private static <T> Thread registrationThread(Class<T> type, Path directory, CountDownLatch start,
        AtomicInteger accepted, ConcurrentLinkedQueue<Throwable> failures,
        ConcurrentLinkedQueue<ConfigHolder<?>> holders) {
        return new Thread(() -> {
            try {
                start.await();
                holders.add(holder(type, directory));
                accepted.incrementAndGet();
            } catch (Exception failure) {
                failures.add(failure);
            }
        });
    }

    private static <T> ConfigHolder<T> holder(Class<T> type, Path directory) {
        return LiteConfig.holder(type).modId("mod").baseDir(directory).create();
    }

    private void reserveSlots(int count, Path directory) {
        for (int index = 0; index < count; index++) {
            RegisteredConfig<WorldPayloadConfig> config = RegisteredConfigs.create(new ConfigSettings<>(
                WorldPayloadConfig.class, new ConfigScope("reserved" + index), directory));
            reservations.add(config.access());
            config.writing(() -> config.pathResolver().bindWorld(WorldPayloadConfig.class, directory.resolve("world")));
            ConfigSyncRegistry.register(config);
            assertTrue(ConfigSyncRegistry.get(config.model().syncId()).wraps(config));
        }
        assertEquals(count, ConfigSyncRegistry.localValues().size());
        assertFalse(Files.exists(directory.resolve("world")));
    }

    private static int manifestSize() {
        return ServerConfigSync.beginHandshake(true).stream().mapToInt(packet -> packet.hashes().size()).sum();
    }

    private static void exceedBuiltInLimit(CollectionConfig value, String field) {
        switch (field) {
            case "list" -> value.list = new ArrayList<>(Collections.nCopies(16_385, 1));
            case "map" -> {
                for (int index = 0; index < 16_385; index++) {
                    value.map.put(Integer.toString(index), index);
                }
            }
            case "set" -> {
                for (int index = 0; index < 16_385; index++) {
                    value.set.add(index);
                }
            }
            case "array" -> value.array = new int[16_385];
            case "text" -> value.text = "x".repeat(Short.MAX_VALUE + 1);
            default -> throw new IllegalArgumentException(field);
        }
    }

    public record SizedValue(int size) {}

    @Config(name = "collections", sync = SyncMode.ENABLED)
    public static class CollectionConfig {
        public List<Integer> list = new ArrayList<>();
        public Map<String, Integer> map = new LinkedHashMap<>();
        public Set<Integer> set = new LinkedHashSet<>();
        public int[] array = new int[0];
        public String text = "";
    }

    @Config(name = "strict-collections", sync = SyncMode.ENABLED, updateFailurePolicy = FailurePolicy.STRICT)
    public static class StrictCollectionConfig extends CollectionConfig {}

    @Config(name = "payload", sync = SyncMode.ENABLED)
    public static class PayloadConfig {
        public SizedValue first = new SizedValue(0);
        @Entry(restart = true)
        public SizedValue second = new SizedValue(0);
    }

    @Config(name = "strict-payload", sync = SyncMode.ENABLED, updateFailurePolicy = FailurePolicy.STRICT,
        readFailurePolicy = FailurePolicy.STRICT)
    public static class StrictPayloadConfig extends PayloadConfig {}

    @Config(name = "world-payload", type = ConfigType.WORLD, sync = SyncMode.ENABLED)
    public static class WorldPayloadConfig extends PayloadConfig {}

    @Config(name = "client-payload", type = ConfigType.CLIENT, sync = SyncMode.ENABLED)
    public static class ClientPayloadConfig extends PayloadConfig {}

    @Config(name = "local-payload")
    public static class LocalPayloadConfig {
        public SizedValue first = new SizedValue(0);
    }

    @Config(name = "oversized-defaults", sync = SyncMode.ENABLED)
    public static class OversizedDefaults extends PayloadConfig {
        public OversizedDefaults() {
            first = new SizedValue(ConfigSyncProtocol.MAX_CONFIG_BYTES);
        }
    }

    @Config(name = "large-restart-defaults", sync = SyncMode.ENABLED)
    public static class LargeRestartDefaults extends PayloadConfig {
        public LargeRestartDefaults() {
            second = new SizedValue(ConfigSyncProtocol.MAX_CONFIG_BYTES / 2);
        }
    }

    @Config(name = "strict-world-payload", type = ConfigType.WORLD, sync = SyncMode.ENABLED,
        readFailurePolicy = FailurePolicy.STRICT)
    public static class StrictWorldPayloadConfig extends PayloadConfig {}
}
