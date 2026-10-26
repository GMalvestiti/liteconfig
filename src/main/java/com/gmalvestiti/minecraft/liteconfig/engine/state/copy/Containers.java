package com.gmalvestiti.minecraft.liteconfig.engine.state.copy;

import java.lang.reflect.Type;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Queue;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.function.IntFunction;

import static java.util.Map.entry;

final class Containers {

    private static final Map<Class<?>, IntFunction<?>> FACTORIES = Map.ofEntries(
        entry(Collection.class, ArrayList::new),
        entry(List.class, ArrayList::new),
        entry(ArrayList.class, ArrayList::new),
        entry(LinkedList.class, size -> new LinkedList<>()),
        entry(Set.class, LinkedHashSet::newLinkedHashSet),
        entry(HashSet.class, HashSet::newHashSet),
        entry(LinkedHashSet.class, LinkedHashSet::newLinkedHashSet),
        entry(SortedSet.class, size -> new TreeSet<>()),
        entry(NavigableSet.class, size -> new TreeSet<>()),
        entry(TreeSet.class, size -> new TreeSet<>()),
        entry(Queue.class, ArrayDeque::new),
        entry(Deque.class, ArrayDeque::new),
        entry(ArrayDeque.class, ArrayDeque::new),
        entry(Map.class, LinkedHashMap::newLinkedHashMap),
        entry(HashMap.class, HashMap::newHashMap),
        entry(LinkedHashMap.class, LinkedHashMap::newLinkedHashMap),
        entry(SortedMap.class, size -> new TreeMap<>()),
        entry(NavigableMap.class, size -> new TreeMap<>()),
        entry(TreeMap.class, size -> new TreeMap<>()),
        entry(ConcurrentMap.class, ConcurrentHashMap::new),
        entry(ConcurrentHashMap.class, ConcurrentHashMap::new),
        entry(ConcurrentNavigableMap.class, size -> new ConcurrentSkipListMap<>()),
        entry(ConcurrentSkipListMap.class, size -> new ConcurrentSkipListMap<>())
    );

    private Containers() {}

    @SuppressWarnings({"unchecked", "rawtypes"})
    static IntFunction<?> factory(Class<?> raw, Type[] arguments) {
        if (raw == EnumSet.class && arguments.length == 1 && arguments[0] instanceof Class<?> element
            && element.isEnum()) {
            return size -> EnumSet.noneOf((Class) element);
        }

        if (raw == EnumMap.class && arguments.length == 2 && arguments[0] instanceof Class<?> key
            && key.isEnum()) {
            return size -> new EnumMap(key);
        }

        return FACTORIES.get(raw);
    }
}
