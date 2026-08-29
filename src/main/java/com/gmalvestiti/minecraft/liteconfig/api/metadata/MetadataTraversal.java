package com.gmalvestiti.minecraft.liteconfig.api.metadata;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.stream.Stream;

final class MetadataTraversal {

    private static final Map<List<ConfigProperty>, Index> INDEXES = Collections.synchronizedMap(new WeakHashMap<>());

    private MetadataTraversal() {}

    static Stream<ConfigProperty> stream(List<ConfigProperty> roots) {
        return index(roots).flattened().stream();
    }

    static Stream<ConfigProperty> stream(ConfigProperty root) {
        return stream(List.of(root));
    }

    static Optional<ConfigProperty> find(List<ConfigProperty> roots, String path) {
        return path == null
            ? Optional.empty()
            : Optional.ofNullable(index(roots).byPath().get(path));
    }

    static List<ConfigProperty> synced(List<ConfigProperty> roots) {
        return index(roots).synced();
    }

    private static Index index(List<ConfigProperty> roots) {
        synchronized (INDEXES) {
            return INDEXES.computeIfAbsent(roots, MetadataTraversal::buildIndex);
        }
    }

    private static Index buildIndex(List<ConfigProperty> roots) {
        List<ConfigProperty> flattened = new ArrayList<>();
        Map<String, ConfigProperty> byPath = new LinkedHashMap<>();
        List<ConfigProperty> synced = new ArrayList<>();

        Iterator<ConfigProperty> properties = iterator(roots);
        while (properties.hasNext()) {
            ConfigProperty property = properties.next();
            flattened.add(property);
            byPath.put(property.path(), property);

            if (property.sync() && property.children().isEmpty()) {
                synced.add(property);
            }
        }

        return new Index(
            List.copyOf(flattened),
            Collections.unmodifiableMap(byPath),
            List.copyOf(synced));
    }

    private static Iterator<ConfigProperty> iterator(List<ConfigProperty> roots) {
        return new Iterator<>() {
            private final Deque<ConfigProperty> pending = initializedWith(roots);

            @Override
            public boolean hasNext() {
                return !pending.isEmpty();
            }

            @Override
            public ConfigProperty next() {
                if (pending.isEmpty()) {
                    throw new NoSuchElementException();
                }
                ConfigProperty property = pending.removeFirst();
                pushChildren(property.children(), pending);
                return property;
            }
        };
    }

    private static Deque<ConfigProperty> initializedWith(List<ConfigProperty> roots) {
        Deque<ConfigProperty> pending = new ArrayDeque<>(roots.size());
        pushChildren(roots, pending);
        return pending;
    }

    private static void pushChildren(
        List<ConfigProperty> properties,
        Deque<ConfigProperty> pending
    ) {
        for (int index = properties.size() - 1; index >= 0; index--) {
            pending.addFirst(properties.get(index));
        }
    }

    private record Index(
        List<ConfigProperty> flattened,
        Map<String, ConfigProperty> byPath,
        List<ConfigProperty> synced
    ) {}
}
