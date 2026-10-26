package com.gmalvestiti.minecraft.liteconfig.engine.state.copy;

import com.gmalvestiti.minecraft.liteconfig.api.LiteConfig;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Ignore;
import com.gmalvestiti.minecraft.liteconfig.storage.ConfigSerialization;
import com.gmalvestiti.minecraft.liteconfig.support.ConfigRegistryIsolation;
import com.gmalvestiti.minecraft.liteconfig.support.TestFixtures;
import com.mojang.serialization.Codec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

@ExtendWith(ConfigRegistryIsolation.class)
class DefaultStateClonerTest {

    private final DefaultStateCloner cloner = new DefaultStateCloner();

    @Test
    void testCopiesSimpleConfig() {
        TestFixtures.SimpleConfig source = new TestFixtures.SimpleConfig();
        source.value = 99;

        TestFixtures.SimpleConfig copy =
            assertInstanceOf(TestFixtures.SimpleConfig.class, cloner.copy(source));
        assertNotNull(copy);
        assertNotSame(source, copy);
        assertEquals(99, copy.value);
    }

    @Test
    void testReturnsNullForNullInput() {
        assertNull(cloner.copy(null));
    }

    @Test
    void testDeepCopiesNestedValues() {
        Values source = Values.filled();

        Values copy = (Values) cloner.copy(source);

        assertNotSame(source.nested, copy.nested);
        assertNotSame(source.nested.list, copy.nested.list);
        assertNotSame(source.matrix, copy.matrix);
        assertNotSame(source.matrix[0], copy.matrix[0]);
        assertNotSame(source.nestedList.get(0), copy.nestedList.get(0));
        assertNotSame(source.nestedMap.get("a"), copy.nestedMap.get("a"));
        assertNotSame(source.listArray[0], copy.listArray[0]);
        assertNotSame(source.listMap.get("k"), copy.listMap.get("k"));
        assertNotSame(source.ints, copy.ints);
        assertNotSame(source.names, copy.names);

        copy.nested.list.add("changed");
        copy.matrix[0][0] = 42;
        copy.nestedList.get(0).count = 42;
        copy.listMap.get("k").add(42);
        copy.ints[0] = 42;

        assertEquals(List.of("one"), source.nested.list);
        assertEquals(1, source.matrix[0][0]);
        assertEquals(5, source.nestedList.get(0).count);
        assertEquals(List.of(1), source.listMap.get("k"));
        assertEquals(1, source.ints[0]);
    }

    @Test
    void testMatchesTheJsonRoundTrip() {
        Values source = Values.filled();

        Object copy = cloner.copy(source);
        Values roundTrip = ConfigSerialization.copy(source, Values.class);

        assertEquals(ConfigSerialization.toTree(roundTrip), ConfigSerialization.toTree(copy));
        assertEquals(ConfigSerialization.toTree(source), ConfigSerialization.toTree(copy));
    }

    @Test
    void testSharesImmutableValues() {
        Values source = Values.filled();

        Values copy = (Values) cloner.copy(source);

        assertSame(source.text, copy.text);
        assertSame(source.id, copy.id);
        assertSame(source.amount, copy.amount);
        assertSame(source.timeout, copy.timeout);
        assertSame(source.mode, copy.mode);
        assertSame(source.point, copy.point);
        assertNull(copy.missing);
    }

    @Test
    void testRebuildsContainersAsMutableInstances() {
        Values source = Values.filled();
        source.nested.list = List.of("fixed");

        Values copy = (Values) cloner.copy(source);

        assertInstanceOf(ArrayList.class, copy.nested.list);
        assertInstanceOf(LinkedHashSet.class, copy.set);
        assertInstanceOf(LinkedHashMap.class, copy.nestedMap);
        assertInstanceOf(TreeMap.class, copy.sorted);
        assertInstanceOf(ArrayDeque.class, copy.deque);
        assertInstanceOf(HashMap.class, copy.hash);
        copy.nested.list.add("more");
        assertEquals(List.of("fixed"), source.nested.list);
    }

    @Test
    void testKeepsIgnoredFieldsByReference() {
        Values source = Values.filled();
        source.ignored = 99;
        source.cache = new ArrayList<>();

        Values copy = (Values) cloner.copy(source);

        assertEquals(99, copy.ignored);
        assertSame(source.cache, copy.cache);
    }

    @Test
    void testKeepsIgnoredFieldsOnTheJsonFallback() {
        NoDefaultConstructor source = new NoDefaultConstructor(3);
        source.cache = new Object();

        NoDefaultConstructor copy = (NoDefaultConstructor) cloner.copy(source);

        assertEquals(3, copy.value);
        assertSame(source.cache, copy.cache);
    }

    @Test
    void testCopiesSubclassValuesAsTheDeclaredType() {
        Values source = Values.filled();
        source.nested = new SpecialNested();

        Values copy = (Values) cloner.copy(source);

        assertSame(Nested.class, copy.nested.getClass());
        assertEquals(source.nested.list, copy.nested.list);
    }

    @Test
    void testFallsBackForTypesWithoutDirectCopy() {
        Values source = Values.filled();

        Values copy = (Values) cloner.copy(source);

        assertNotSame(source.enumMap, copy.enumMap);
        assertEquals(source.enumMap, copy.enumMap);
        assertNotSame(source.box, copy.box);
        assertEquals(3, copy.box.value.count);
        assertNotSame(source.counter, copy.counter);
        assertEquals(source.counter.get(), copy.counter.get());
    }

    @Test
    void testCopiesGenericRootLikeTheJsonRoundTrip() {
        Box<Nested> source = new Box<>();
        source.value = new Nested();

        Object copy = cloner.copy(source);

        assertNotSame(source.value, ((Box<?>) copy).value);
        assertEquals(ConfigSerialization.toTree(ConfigSerialization.copy(source, Box.class)), ConfigSerialization.toTree(copy));
    }

    @Test
    void testUsesRegisteredCodecsAfterRegistration() {
        CodecHolder source = new CodecHolder();
        source.value = new Counted(4);

        assertEquals(4, ((CodecHolder) cloner.copy(source)).value.count);

        LiteConfig.codecs().registerCodec(Counted.class, Codec.INT.xmap(Counted::new, value -> value.count + 1));

        assertEquals(5, ((CodecHolder) cloner.copy(source)).value.count);
    }

    @Test
    void testCopiesArraysOfObjects() {
        Values source = Values.filled();

        Values copy = (Values) cloner.copy(source);

        assertArrayEquals(source.names, copy.names);
        assertEquals(source.nestedArray.length, copy.nestedArray.length);
        assertNotSame(source.nestedArray[0], copy.nestedArray[0]);
        assertNull(copy.nestedArray[1]);
    }

    @Test
    void testCopiesRecordsWithMutableComponents() {
        Bag source = new Bag(new ArrayList<>(List.of("a")), new Point(1, 2));

        Bag copy = (Bag) cloner.copy(source);

        assertNotSame(source, copy);
        assertNotSame(source.items(), copy.items());
        assertEquals(source, copy);
    }

    @Test
    void testCopiesEnumContainersNatively() {
        EnumHolder source = new EnumHolder();
        source.set = java.util.EnumSet.of(Mode.OFF);
        source.map = new EnumMap<>(Map.of(Mode.ON, 3));

        EnumHolder copy = (EnumHolder) cloner.copy(source);

        assertNotSame(source.set, copy.set);
        assertNotSame(source.map, copy.map);
        assertEquals(source.set, copy.set);
        assertEquals(source.map, copy.map);
        assertInstanceOf(EnumMap.class, copy.map);
        assertInstanceOf(java.util.EnumSet.class, copy.set);
    }

    enum Mode { ON, OFF }

    record Point(int x, int y) {}

    record Bag(List<String> items, Point point) {}

    static class EnumHolder {
        java.util.EnumSet<Mode> set;
        EnumMap<Mode, Integer> map;
    }

    static class Nested {
        int count = 5;
        List<String> list = new ArrayList<>(List.of("one"));
    }

    static class SpecialNested extends Nested {
        String extra = "extra";
    }

    static class Box<T> {
        T value;
    }

    static class Counted {
        int count;

        Counted() {}

        Counted(int count) {
            this.count = count;
        }
    }

    static class CodecHolder {
        Counted value;
    }

    static class NoDefaultConstructor {
        int value;
        @Ignore
        Object cache;

        NoDefaultConstructor(int value) {
            this.value = value;
        }
    }

    static class Values {
        boolean flag = true;
        long big = 9L;
        double ratio = 0.5;
        char letter = 'x';
        String text;
        String missing;
        UUID id;
        BigDecimal amount;
        Duration timeout;
        Mode mode;
        Point point;
        @Ignore
        int ignored = 7;
        @Ignore
        List<String> cache;
        Nested nested;
        int[][] matrix;
        int[] ints;
        String[] names;
        Nested[] nestedArray;
        List<Integer>[] listArray;
        List<Nested> nestedList;
        Map<String, Nested> nestedMap;
        Map<String, List<Integer>> listMap;
        Set<String> set;
        TreeMap<String, Integer> sorted;
        Deque<String> deque;
        HashMap<Mode, Integer> hash;
        EnumMap<Mode, Integer> enumMap;
        Box<Nested> box;
        AtomicInteger counter;

        @SuppressWarnings("unchecked")
        static Values filled() {
            Values values = new Values();
            values.text = "hello";
            values.id = UUID.randomUUID();
            values.amount = new BigDecimal("1.25");
            values.timeout = Duration.ofSeconds(3);
            values.mode = Mode.OFF;
            values.point = new Point(1, 2);
            values.nested = new Nested();
            values.matrix = new int[][] {{1, 2}, {3}};
            values.ints = new int[] {1, 2, 3};
            values.names = new String[] {"a", null, "c"};
            values.nestedArray = new Nested[] {new Nested(), null};
            values.listArray = new List[] {new ArrayList<>(List.of(1, 2))};
            values.nestedList = new ArrayList<>(List.of(new Nested()));
            values.nestedMap = new LinkedHashMap<>(Map.of("a", new Nested()));
            values.listMap = new LinkedHashMap<>(Map.of("k", new ArrayList<>(List.of(1))));
            values.set = new LinkedHashSet<>(List.of("x", "y"));
            values.sorted = new TreeMap<>(Map.of("b", 2, "a", 1));
            values.deque = new ArrayDeque<>(List.of("first", "second"));
            values.hash = new HashMap<>(Map.of(Mode.ON, 1));
            values.enumMap = new EnumMap<>(Map.of(Mode.ON, 2));
            values.box = new Box<>();
            values.box.value = new Nested();
            values.box.value.count = 3;
            values.counter = new AtomicInteger(8);
            return values;
        }
    }
}
