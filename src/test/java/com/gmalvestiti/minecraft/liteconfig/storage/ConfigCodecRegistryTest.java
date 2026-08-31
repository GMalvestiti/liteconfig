package com.gmalvestiti.minecraft.liteconfig.storage;

import com.gmalvestiti.minecraft.liteconfig.api.ConfigHolder;
import com.gmalvestiti.minecraft.liteconfig.api.ConfigFormat;
import com.gmalvestiti.minecraft.liteconfig.api.LiteConfig;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Config;
import com.gmalvestiti.minecraft.liteconfig.metadata.ConfigFieldPlan;
import com.gmalvestiti.minecraft.liteconfig.registry.ConfigCodecRegistry;
import com.gmalvestiti.minecraft.liteconfig.support.ConfigRegistryIsolation;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(ConfigRegistryIsolation.class)
class ConfigCodecRegistryTest {

    private static final Type PALETTE_TYPE = new TypeToken<List<RgbColor>>() {}.getType();
    private static final Codec<RgbColor> COLOR_CODEC =
        Codec.STRING.xmap(ConfigCodecRegistryTest::parse, ConfigCodecRegistryTest::format);
    private static final Codec<List<RgbColor>> PALETTE_CODEC = Codec.STRING.xmap(
        ConfigCodecRegistryTest::parsePalette,
        colors -> colors.stream().map(ConfigCodecRegistryTest::format)
            .reduce((left, right) -> left + ";" + right).orElse(""));
    private static final Codec<IntRange> RANGE_CODEC = RecordCodecBuilder.create(instance -> instance.group(
        Codec.INT.fieldOf("minimum").forGetter(IntRange::minimum),
        Codec.INT.fieldOf("maximum").forGetter(IntRange::maximum)
    ).apply(instance, IntRange::new));
    private static final StreamCodec<ByteBuf, IntRange> RANGE_STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.VAR_INT, IntRange::minimum,
        ByteBufCodecs.VAR_INT, IntRange::maximum,
        IntRange::new
    );

    @Test
    void testRoundTripsClassAndGenericCodecsThroughAFile(@TempDir Path tempDir) throws Exception {
        registerCodecs();
        ConfigHolder<CodecConfig> holder = LiteConfig.holder(CodecConfig.class)
            .modId("mod")
            .baseDir(tempDir)
            .create();

        holder.updateAndSave(config -> {
            config.primary = new RgbColor(3, 4);
            config.palette = List.of(new RgbColor(8, 9), new RgbColor(10, 11));
        });

        String stored = Files.readString(tempDir.resolve("codec-values.json5"));
        assertTrue(stored.contains("\"primary\": \"3:4\""));
        assertTrue(stored.contains("\"palette\": \"8:9;10:11\""));

        holder.update(config -> {
            config.primary = new RgbColor(0, 0);
            config.palette = List.of();
        });
        holder.load();

        assertEquals(new RgbColor(3, 4), holder.data().primary);
        assertEquals(List.of(new RgbColor(8, 9), new RgbColor(10, 11)), holder.data().palette);
    }

    @Test
    void testRegistersProcessWideCodecsWhileCreatingAHolder(@TempDir Path tempDir) throws Exception {
        Codec<HolderValue> codec = Codec.INT.xmap(HolderValue::new, HolderValue::value);
        StreamCodec<ByteBuf, HolderValue> streamCodec =
            ByteBufCodecs.VAR_INT.map(HolderValue::new, HolderValue::value);
        ConfigHolder<HolderCodecConfig> holder = LiteConfig.holder(
                HolderCodecConfig.class,
                codecs -> codecs
                    .registerCodec(HolderValue.class, codec)
                    .registerStreamCodec(HolderValue.class, streamCodec))
            .modId("mod")
            .baseDir(tempDir)
            .create();

        holder.updateAndSave(config -> config.value = new HolderValue(4));

        assertTrue(LiteConfig.codecs().find(HolderValue.class).isPresent());
        assertTrue(LiteConfig.codecs().findStream(HolderValue.class).isPresent());
        assertTrue(Files.readString(tempDir.resolve("holder-codec.json5")).contains("\"value\": 4"));
    }

    @Test
    void testFallsBackToReflectionWhenACustomCodecRejectsAValue(@TempDir Path tempDir) {
        Codec<FallbackValue> codec = Codec.INT.flatXmap(
            value -> value >= 0
                ? DataResult.success(new FallbackValue(value))
                : DataResult.error(() -> "negative value"),
            value -> value.value() >= 0
                ? DataResult.success(value.value())
                : DataResult.error(() -> "negative value"));
        ConfigHolder<FallbackCodecConfig> holder = LiteConfig.holder(
                FallbackCodecConfig.class,
                codecs -> codecs.registerCodec(FallbackValue.class, codec))
            .modId("mod")
            .baseDir(tempDir)
            .create();

        holder.updateAndSave(config -> config.value = new FallbackValue(-4));
        holder.update(config -> config.value = new FallbackValue(2));
        holder.load();

        assertEquals(new FallbackValue(-4), holder.data().value);
    }

    @Test
    void testRoundTripsClassAndGenericCodecsThroughToml(@TempDir Path tempDir) throws Exception {
        registerCodecs();
        ConfigHolder<TomlCodecConfig> holder = LiteConfig.holder(TomlCodecConfig.class)
            .modId("mod")
            .baseDir(tempDir)
            .create();

        holder.updateAndSave(config -> {
            config.primary = new RgbColor(3, 4);
            config.palette = List.of(new RgbColor(8, 9), new RgbColor(10, 11));
        });

        String stored = Files.readString(tempDir.resolve("codec-values.toml"));
        assertTrue(stored.contains("primary = \"3:4\""));
        assertTrue(stored.contains("palette = \"8:9;10:11\""));

        holder.update(config -> {
            config.primary = new RgbColor(0, 0);
            config.palette = List.of();
        });
        holder.load();

        assertEquals(new RgbColor(3, 4), holder.data().primary);
        assertEquals(List.of(new RgbColor(8, 9), new RgbColor(10, 11)), holder.data().palette);
    }

    @Test
    void testRejectsNullCodecRegistrations() {
        assertThrows(
            NullPointerException.class,
            () -> LiteConfig.codecs().registerCodec((Class<RgbColor>) null, COLOR_CODEC)
        );
        assertThrows(
            NullPointerException.class,
            () -> LiteConfig.codecs().registerCodec(RgbColor.class, (Codec<RgbColor>) null)
        );
        assertThrows(
            NullPointerException.class,
            () -> LiteConfig.codecs().registerStreamCodec(
                RgbColor.class, (StreamCodec<ByteBuf, RgbColor>) null)
        );
    }

    @Test
    void testRegistersFileAndNetworkCodecsIndependentlyInAChain() {
        LiteConfig.codecs()
            .registerStreamCodec(IntRange.class, RANGE_STREAM_CODEC)
            .registerCodec(IntRange.class, RANGE_CODEC);

        assertTrue(LiteConfig.codecs().find(IntRange.class).isPresent());
        assertTrue(LiteConfig.codecs().findStream(IntRange.class).isPresent());
        assertEquals(
            RANGE_STREAM_CODEC,
            LiteConfig.codecs().findStream(IntRange.class).orElseThrow());
    }

    @Test
    void testRegistersAStableNetworkCodecSchemaVersion() {
        StreamCodec<ByteBuf, SchemaValue> streamCodec = ByteBufCodecs.VAR_INT.map(
            SchemaValue::new,
            SchemaValue::value);
        LiteConfig.codecs().registerStreamCodec(
            SchemaValue.class, "2", streamCodec);

        assertEquals(
            "2",
            LiteConfig.codecs().findStreamSchema(SchemaValue.class).orElseThrow());
    }

    @Test
    void testRejectsABlankNetworkCodecSchemaVersion() {
        assertThrows(
            IllegalArgumentException.class,
            () -> LiteConfig.codecs().registerStreamCodec(
                IntRange.class, " ", RANGE_STREAM_CODEC));
    }

    @Test
    void testOnlyFileCodecsInvalidateConfiguredSerialization() {
        ConfigCodecRegistry codecs = LiteConfig.codecs();
        int initialGeneration = codecs.generation();
        StreamCodec<ByteBuf, GenerationValue> streamCodec = ByteBufCodecs.VAR_INT.map(
            GenerationValue::new,
            GenerationValue::value
        );
        Codec<GenerationValue> codec = Codec.INT.xmap(GenerationValue::new, GenerationValue::value);

        codecs.registerStreamCodec(GenerationValue.class, streamCodec);
        assertEquals(initialGeneration, codecs.generation());

        codecs.registerCodec(GenerationValue.class, codec);
        assertEquals(initialGeneration + 1, codecs.generation());
    }

    @Test
    void testAllowsANetworkCodecWithoutAFileCodec() {
        StreamCodec<ByteBuf, NetworkOnlyValue> streamCodec = ByteBufCodecs.VAR_INT.map(
            NetworkOnlyValue::new,
            NetworkOnlyValue::value
        );

        LiteConfig.codecs().registerStreamCodec(NetworkOnlyValue.class, streamCodec);

        assertTrue(LiteConfig.codecs().find(NetworkOnlyValue.class).isEmpty());
        assertTrue(LiteConfig.codecs().findStream(NetworkOnlyValue.class).isPresent());
        assertEquals(7, ConfigBinder.copy(new NetworkOnlyValue(7), NetworkOnlyValue.class).value());
    }

    @Test
    void testRejectsDuplicateRegistrationsOfTheSameKind() {
        Codec<DuplicateValue> codec = Codec.INT.xmap(DuplicateValue::new, DuplicateValue::value);
        StreamCodec<ByteBuf, DuplicateValue> streamCodec = ByteBufCodecs.VAR_INT.map(
            DuplicateValue::new,
            DuplicateValue::value
        );
        LiteConfig.codecs()
            .registerCodec(DuplicateValue.class, codec)
            .registerStreamCodec(DuplicateValue.class, streamCodec);

        assertThrows(
            IllegalStateException.class,
            () -> LiteConfig.codecs().registerCodec(DuplicateValue.class, codec)
        );
        assertThrows(
            IllegalStateException.class,
            () -> LiteConfig.codecs().registerStreamCodec(DuplicateValue.class, streamCodec)
        );
    }

    @Test
    void testRejectsRegistrationAfterFieldPlanningStarts() {
        ConfigFieldPlan.of(FreezeConfig.class);

        assertThrows(
            IllegalStateException.class,
            () -> LiteConfig.codecs().registerCodec(FrozenValue.class, frozenCodec()));
        LiteConfig.codecs().registerCodec(
            UnplannedValue.class,
            Codec.INT.xmap(UnplannedValue::new, UnplannedValue::value));
    }

    @Test
    void testParameterizedValuesUseOnlyAnExactCodecRegistration() throws NoSuchFieldException {
        Type valueType = GenericConfig.class.getDeclaredField("value").getGenericType();
        Codec<GenericValue<?>> codec = Codec.STRING.xmap(
            ignored -> new GenericValue<>(),
            ignored -> "value");

        LiteConfig.codecs().registerCodec((Type) GenericValue.class, codec);
        GenericValue<String> original = new GenericValue<>();
        original.value = "preserved";
        Gson rawOnly = LiteConfig.codecs().configure(new GsonBuilder()).create();
        GenericValue<?> reflectedCopy = (GenericValue<?>) rawOnly.fromJson(
            rawOnly.toJson(original, valueType),
            valueType);
        assertEquals("preserved", reflectedCopy.value);

        LiteConfig.codecs().registerCodec(valueType, codec);
        assertTrue(LiteConfig.codecs().find(valueType).isPresent());
        Gson exact = LiteConfig.codecs().configure(new GsonBuilder()).create();
        GenericValue<?> codecCopy = (GenericValue<?>) exact.fromJson(
            exact.toJson(original, valueType),
            valueType);
        assertNull(codecCopy.value);
    }

    @Test
    void testRoundTripsRegisteredCodecsThroughFilesAndSyncBytes(@TempDir Path tempDir) throws Exception {
        LiteConfig.codecs()
            .registerCodec(NetworkRange.class, NETWORK_RANGE_CODEC)
            .registerStreamCodec(NetworkRange.class, NETWORK_RANGE_STREAM_CODEC);
        ConfigHolder<NetworkCodecConfig> holder = LiteConfig.holder(NetworkCodecConfig.class)
            .modId("mod")
            .baseDir(tempDir)
            .create();

        holder.updateAndSave(config -> config.range = new NetworkRange(4, 12));
        holder.update(config -> config.range = new NetworkRange(0, 1));
        holder.load();

        StreamCodec<ByteBuf, NetworkRange> streamCodec =
            LiteConfig.codecs().findStream(NetworkRange.class).orElseThrow();
        ByteBuf buffer = Unpooled.buffer();
        NetworkRange decoded;
        try {
            streamCodec.encode(buffer, new NetworkRange(8, 20));
            decoded = streamCodec.decode(buffer);
        } finally {
            buffer.release();
        }

        assertEquals(new NetworkRange(4, 12), holder.data().range);
        assertEquals(new NetworkRange(8, 20), decoded);
        assertTrue(Files.readString(tempDir.resolve("network-codec.json5")).contains("\"minimum\": 4"));
    }

    private static void registerCodecs() {
        if (LiteConfig.codecs().find(RgbColor.class).isEmpty()) {
            LiteConfig.codecs().registerCodec(RgbColor.class, COLOR_CODEC);
        }
        if (LiteConfig.codecs().find(PALETTE_TYPE).isEmpty()) {
            LiteConfig.codecs().registerCodec(PALETTE_TYPE, PALETTE_CODEC);
        }
    }

    private static List<RgbColor> parsePalette(String encoded) {
        return encoded.isEmpty()
            ? List.of()
            : List.of(encoded.split(";")).stream().map(ConfigCodecRegistryTest::parse).toList();
    }

    private static String format(RgbColor color) {
        return color.red() + ":" + color.blue();
    }

    private static RgbColor parse(String encoded) {
        String[] components = encoded.split(":", -1);
        return new RgbColor(Integer.parseInt(components[0]), Integer.parseInt(components[1]));
    }

    @Config(name = "codec-values")
    public static class CodecConfig {
        public RgbColor primary = new RgbColor(1, 2);
        public List<RgbColor> palette = List.of(new RgbColor(5, 6));
    }

    @Config(name = "codec-values", format = ConfigFormat.TOML)
    public static class TomlCodecConfig {
        public RgbColor primary = new RgbColor(1, 2);
        public List<RgbColor> palette = List.of(new RgbColor(5, 6));
    }

    @Config(name = "network-codec")
    public static class NetworkCodecConfig {
        public NetworkRange range = new NetworkRange(2, 6);
    }

    @Config(name = "holder-codec")
    public static class HolderCodecConfig {
        public HolderValue value = new HolderValue(2);
    }

    @Config(name = "fallback-codec")
    public static class FallbackCodecConfig {
        public FallbackValue value = new FallbackValue(2);
    }

    public record RgbColor(int red, int blue) {}

    public record IntRange(int minimum, int maximum) {}

    public record NetworkRange(int minimum, int maximum) {}

    public record HolderValue(int value) {}

    public record FallbackValue(int value) {}

    public record NetworkOnlyValue(int value) {}

    public record DuplicateValue(int value) {}

    public record GenerationValue(int value) {}

    public record SchemaValue(int value) {}

    @Config(name = "freeze")
    public static class FreezeConfig {
        public FrozenValue value = new FrozenValue(1);
    }

    public record FrozenValue(int value) {}

    public record UnplannedValue(int value) {}

    @Config(name = "generic")
    public static class GenericConfig {
        public GenericValue<String> value = new GenericValue<>();
    }

    public static class GenericValue<T> {
        public T value;
    }

    private static Codec<FrozenValue> frozenCodec() {
        return Codec.INT.xmap(FrozenValue::new, FrozenValue::value);
    }

    private static final Codec<NetworkRange> NETWORK_RANGE_CODEC =
        RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("minimum").forGetter(NetworkRange::minimum),
            Codec.INT.fieldOf("maximum").forGetter(NetworkRange::maximum)
        ).apply(instance, NetworkRange::new));
    private static final StreamCodec<ByteBuf, NetworkRange> NETWORK_RANGE_STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, NetworkRange::minimum,
            ByteBufCodecs.VAR_INT, NetworkRange::maximum,
            NetworkRange::new
        );
}
