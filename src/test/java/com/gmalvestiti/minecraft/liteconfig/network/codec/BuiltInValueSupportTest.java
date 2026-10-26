package com.gmalvestiti.minecraft.liteconfig.network.codec;

import com.gmalvestiti.minecraft.liteconfig.api.ConfigFormat;
import com.gmalvestiti.minecraft.liteconfig.api.LiteConfig;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Config;
import com.gmalvestiti.minecraft.liteconfig.api.metadata.ConfigProperty;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigScope;
import com.gmalvestiti.minecraft.liteconfig.metadata.ConfigFieldPlan;
import com.gmalvestiti.minecraft.liteconfig.metadata.ConfigMetadataFactory;
import com.gmalvestiti.minecraft.liteconfig.reflection.PersistedFields;
import com.gmalvestiti.minecraft.liteconfig.storage.ConfigSerialization;
import com.gmalvestiti.minecraft.liteconfig.storage.format.ConfigTextFormat;
import com.gmalvestiti.minecraft.liteconfig.support.ConfigRegistryIsolation;
import com.mojang.serialization.Codec;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.codec.ByteBufCodecs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(ConfigRegistryIsolation.class)
class BuiltInValueSupportTest {

    private static final ConfigScope SCOPE = new ConfigScope("builtins");

    @Test
    void roundTripsEveryPrimitiveArrayAndImmutableScalarThroughBothFormatsAndCopy() throws Exception {
        Values source = new Values();
        for (ConfigFormat format : ConfigFormat.values()) {
            ConfigTextFormat text = ConfigTextFormat.of(format);
            String encoded = text.writeTree(ConfigSerialization.toTree(source).getAsJsonObject(), Values.class);
            Values decoded = ConfigSerialization.fromTree(text.readTree(encoded), Values.class);
            assertArrayEquals(source.floats, decoded.floats, format.name());
            assertArrayEquals(source.doubles, decoded.doubles, format.name());
            assertValues(source, decoded);
        }
        Values copy = ConfigSerialization.copy(source, Values.class);
        assertValues(source, copy);
        copy.ints[0] = 100;
        copy.nested.get("values").getFirst()[0] = 100;
        assertEquals(Integer.MIN_VALUE, source.ints[0]);
        assertEquals(1, source.nested.get("values").getFirst()[0]);
        for (Field field : Values.class.getFields()) {
            assertFalse(PersistedFields.descendable(field.getGenericType()), field.getName());
        }
    }

    @Test
    void roundTripsTypedAndGenericArraysContainersNullElementsAndScalarsOnWire() throws Exception {
        Values source = new Values();
        for (Field field : Values.class.getFields()) {
            Object value = field.get(source);
            Object decoded = roundTrip(field.getGenericType(), value);
            assertEquals(ConfigSerialization.toTree(value), ConfigSerialization.toTree(decoded), field.getName());
        }
        assertArrayEquals(new byte[16_384], (byte[]) roundTrip(byte[].class, new byte[16_384]));
        assertArrayEquals(new int[16_384], (int[]) roundTrip(int[].class, new int[16_384]));
        assertNull(roundTrip(String[].class, null));
        assertNull(roundTrip(Long[].class, null));
        assertArrayEquals(new Long[]{Long.MIN_VALUE, null, Long.MAX_VALUE},
            (Long[]) roundTrip(Long[].class, new Long[]{Long.MIN_VALUE, null, Long.MAX_VALUE}));
        assertTrue(Arrays.deepEquals(new Object[]{new String[]{"one", null}},
            new Object[]{roundTrip(String[].class, new String[]{"one", null})}));
        assertEquals(List.of("one"), Array.get(roundTrip(
            GenericArrays.class.getField("lists").getGenericType(),
            new List<?>[]{List.of("one"), null}), 0));
        assertNotEquals(resolve(int[].class).schema(), resolve(Integer[].class).schema());
    }

    @Test
    void rejectsArrayBoundsTruncationInvalidCharactersAndMalformedScalars() {
        ByteBuf buffer = Unpooled.buffer();
        try {
            for (int size : new int[]{-1, 16_385}) {
                buffer.clear();
                ByteBufCodecs.BOOL.encode(buffer, true);
                ByteBufCodecs.VAR_INT.encode(buffer, size);
                assertThrows(DecoderException.class, () -> resolve(int[].class).codec().decode(buffer));
            }
            buffer.clear();
            ByteBufCodecs.BOOL.encode(buffer, true);
            ByteBufCodecs.VAR_INT.encode(buffer, 2);
            buffer.writeInt(1);
            assertThrows(DecoderException.class, () -> resolve(int[].class).codec().decode(buffer));
            buffer.clear();
            assertThrows(IllegalArgumentException.class,
                () -> resolve(byte[].class).codec().encode(buffer, new byte[16_385]));
            buffer.clear();
            ByteBufCodecs.VAR_INT.encode(buffer, 65_536);
            assertThrows(DecoderException.class, () -> resolve(char.class).codec().decode(buffer));
            buffer.clear();
            ByteBufCodecs.BOOL.encode(buffer, true);
            ByteBufCodecs.STRING_UTF8.encode(buffer, "not-an-instant");
            assertThrows(DecoderException.class, () -> resolve(Instant.class).codec().decode(buffer));
            buffer.clear();
            assertThrows(RuntimeException.class, () -> resolve(BigInteger.class).codec().encode(
                buffer, new BigInteger("9".repeat(32_768))));
        } finally {
            buffer.release();
        }
        assertThrows(RuntimeException.class, () -> ConfigSerialization.fromTree(
            com.google.gson.JsonParser.parseString("{\"instant\":\"invalid\"}"), Values.class));
    }

    @Test
    void customScalarAndArrayRegistrationsTakePrecedence() throws ReflectiveOperationException {
        Object registry = LiteConfig.codecs();
        Field registrations = registry.getClass().getDeclaredField("registrations");
        Field generation = registry.getClass().getDeclaredField("generation");
        registrations.setAccessible(true);
        generation.setAccessible(true);
        Object originalRegistrations = registrations.get(registry);
        try {
            LiteConfig.codecs().registerCodec(BigInteger.class,
                Codec.STRING.xmap(text -> new BigInteger(text.substring(1)), value -> "n" + value));
            assertEquals("n" + new Values().bigInteger,
                ConfigSerialization.toTree(new Values()).getAsJsonObject().get("bigInteger").getAsString());
            assertValues(new Values(), ConfigSerialization.copy(new Values(), Values.class));
            LiteConfig.codecs().registerStreamCodec(int.class, ByteBufCodecs.VAR_INT);
            assertArrayEquals(new int[]{1, 2}, (int[]) roundTrip(int[].class, new int[]{1, 2}));
            LiteConfig.codecs().registerStreamCodec(long.class, ByteBufCodecs.VAR_LONG);
            assertArrayEquals(new long[]{1, 2, Long.MIN_VALUE, Long.MAX_VALUE},
                (long[]) roundTrip(long[].class, new long[]{1, 2, Long.MIN_VALUE, Long.MAX_VALUE}));
            assertTrue(resolve(long[].class).schema().contains("registered:long"));
            LiteConfig.codecs().registerStreamCodec(String[].class,
                ByteBufCodecs.STRING_UTF8.map(text -> new String[]{text}, values -> values[0]));
            assertTrue(resolve(String[].class).schema().startsWith("registered:"));
            assertArrayEquals(new String[]{"custom"}, (String[]) roundTrip(String[].class, new String[]{"custom"}));
        } finally {
            registrations.set(registry, originalRegistrations);
            generation.setInt(registry, generation.getInt(registry) + 1);
        }
    }

    @Test
    void metadataDefensivelyCopiesArraysNestedInsideContainers() {
        ConfigProperty property = ConfigMetadataFactory.create(Values.class, SCOPE,
            ConfigFieldPlan.of(Values.class)).properties().stream()
            .filter(value -> value.fieldName().equals("nested")).findFirst().orElseThrow();
        Map<?, ?> first = (Map<?, ?>) property.defaultValue();
        int[] firstArray = (int[]) ((List<?>) first.get("values")).getFirst();
        firstArray[0] = 99;
        Map<?, ?> second = (Map<?, ?>) property.defaultValue();
        assertEquals(1, ((int[]) ((List<?>) second.get("values")).getFirst())[0]);
        assertThrows(UnsupportedOperationException.class, first::clear);
    }

    @Test
    void rejectsTomlNullArrayElementsRatherThanSilentlyDroppingThem() {
        ConfigTextFormat toml = ConfigTextFormat.of(ConfigFormat.TOML);
        assertThrows(IllegalArgumentException.class, () -> toml.writeTree(
            com.google.gson.JsonParser.parseString("{\"strings\":[\"one\",null,\"two\"]}").getAsJsonObject(),
            Values.class));
    }

    @Test
    void snapshotsArraysOfConcreteSupportedCollectionsWithoutChangingArrayType() {
        for (ConfigProperty property : ConfigMetadataFactory.create(ConcreteArrays.class, SCOPE,
            ConfigFieldPlan.of(ConcreteArrays.class)).properties()) {
            Object[] first = (Object[]) property.defaultValue();
            assertEquals(property.type(), first.getClass());
            if (first[0] instanceof java.util.Collection<?> collection) {
                collection.clear();
            } else {
                ((Map<?, ?>) first[0]).clear();
            }
            Object[] second = (Object[]) property.defaultValue();
            int size = second[0] instanceof java.util.Collection<?> collection
                ? collection.size() : ((Map<?, ?>) second[0]).size();
            assertEquals(1, size, property.fieldName());
        }
    }

    @Test
    void roundTripsParameterizedObjectArraysThroughPersistenceAndCopy() {
        GenericArrays values = ConfigSerialization.fromTree(
            com.google.gson.JsonParser.parseString("{\"lists\":[[\"one\"],[\"two\"]]}"), GenericArrays.class);
        for (ConfigFormat format : ConfigFormat.values()) {
            ConfigTextFormat text = ConfigTextFormat.of(format);
            GenericArrays decoded = ConfigSerialization.fromTree(text.readTree(
                text.writeTree(ConfigSerialization.toTree(values).getAsJsonObject(), GenericArrays.class)),
                GenericArrays.class);
            assertEquals(List.of("one"), decoded.lists[0]);
            assertEquals(List.of("two"), decoded.lists[1]);
        }
        GenericArrays copied = ConfigSerialization.copy(values, GenericArrays.class);
        copied.lists[0].clear();
        assertEquals(List.of("one"), values.lists[0]);
    }

    @Test
    void rejectsUntypedArraysAndContainersOnWire() {
        ConfigProperty property = ConfigMetadataFactory.create(Values.class, SCOPE,
            ConfigFieldPlan.of(Values.class)).properties().getFirst();
        for (Type unsupported : new Type[]{Object.class, Object[].class, List.class, List[].class,
            java.util.Optional.class}) {
            assertThrows(com.gmalvestiti.minecraft.liteconfig.exception.LiteConfigException.class,
                () -> SyncValueCodecs.resolve(unsupported, property, SCOPE));
        }
    }

    @Test
    void roundTripsPermittedConcreteGenericContainerArrays() throws IllegalAccessException {
        ContainerArrays source = ConfigSerialization.fromTree(com.google.gson.JsonParser.parseString("""
            {"lists":[["one"]],"maps":[{"one":1}],"linkedMaps":[{"one":1}],
             "sets":[["one"]],"linkedSets":[["one"]]}
            """), ContainerArrays.class);
        for (Field field : ContainerArrays.class.getFields()) {
            Object value = field.get(source);
            Object decoded = roundTrip(field.getGenericType(), value);
            assertEquals(value.getClass(), decoded.getClass(), field.getName());
            assertEquals(ConfigSerialization.toTree(value), ConfigSerialization.toTree(decoded), field.getName());
        }
    }

    private static SyncValueCodecs.Resolved resolve(Type type) {
        return SyncValueCodecs.resolve(type, null, SCOPE);
    }

    private static Object roundTrip(Type type, Object value) {
        ByteBuf buffer = Unpooled.buffer();
        try {
            SyncValueCodecs.Resolved resolved = resolve(type);
            resolved.codec().encode(buffer, value);
            Object decoded = resolved.codec().decode(buffer);
            assertEquals(0, buffer.readableBytes());
            return decoded;
        } finally {
            buffer.release();
        }
    }

    private static void assertValues(Values expected, Values actual) throws IllegalAccessException {
        for (Field field : Values.class.getFields()) {
            assertEquals(ConfigSerialization.toTree(field.get(expected)), ConfigSerialization.toTree(field.get(actual)),
                field.getName());
        }
    }

    public static class GenericArrays {
        public List<String>[] lists;
    }

    public static class ContainerArrays {
        public ArrayList<String>[] lists;
        public java.util.HashMap<String, Integer>[] maps;
        public java.util.LinkedHashMap<String, Integer>[] linkedMaps;
        public java.util.HashSet<String>[] sets;
        public java.util.LinkedHashSet<String>[] linkedSets;
    }

    @Config(name = "concrete-arrays")
    public static class ConcreteArrays {
        @SuppressWarnings("rawtypes")
        public ArrayList[] lists = {new ArrayList<>(List.of("one"))};
        @SuppressWarnings("rawtypes")
        public java.util.HashMap[] maps = {new java.util.HashMap<>(Map.of("one", 1))};
        @SuppressWarnings("rawtypes")
        public java.util.LinkedHashMap[] linkedMaps = {new java.util.LinkedHashMap<>(Map.of("one", 1))};
        @SuppressWarnings("rawtypes")
        public java.util.HashSet[] sets = {new java.util.HashSet<>(List.of("one"))};
        @SuppressWarnings("rawtypes")
        public java.util.LinkedHashSet[] linkedSets = {new java.util.LinkedHashSet<>(List.of("one"))};
    }

    @Config(name = "built-in-values")
    public static class Values {
        public boolean[] flags = {true, false};
        public byte[] bytes = {Byte.MIN_VALUE, Byte.MAX_VALUE};
        public short[] shorts = {Short.MIN_VALUE, Short.MAX_VALUE};
        public char[] chars = {Character.MIN_VALUE, Character.MAX_VALUE, 'a'};
        public int[] ints = {Integer.MIN_VALUE, Integer.MAX_VALUE};
        public long[] longs = {Long.MIN_VALUE, Long.MAX_VALUE};
        public float[] floats =         {-123456.75F, 0.015625F};
        public double[] doubles = {-12345.125, 0.001953125};
        public Integer[] boxed = {Integer.MIN_VALUE, Integer.MAX_VALUE};
        public String[] strings = {"one", "two"};
        public int[][] matrix = {{1, 2}, {3}};
        public UUID uuid = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff");
        public BigInteger bigInteger = new BigInteger("999999999999999999999999999999999999");
        public BigDecimal decimal = new BigDecimal("12345678901234567890.12345678901234567890");
        public Duration duration = Duration.ofSeconds(-1, 123_456_789);
        public Instant instant = Instant.parse("2000-01-02T03:04:05.123456789Z");
        public LocalDate date = LocalDate.of(2000, 2, 29);
        public LocalTime time = LocalTime.of(23, 59, 59, 999_999_999);
        public LocalDateTime dateTime = LocalDateTime.of(date, time);
        public Map<String, List<int[]>> nested = Map.of("values", List.of(new int[]{1, 2}));
    }
}
