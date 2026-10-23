package com.gmalvestiti.minecraft.liteconfig.api.metadata;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

final class MetadataTraversal {

    private static final LoadingCache<List<ConfigProperty>, Index> INDEXES =
        CacheBuilder
            .newBuilder()
            .weakKeys()
            .build(CacheLoader.from(MetadataTraversal::buildIndex));

    private MetadataTraversal() {}

    static Stream<ConfigProperty> stream(List<ConfigProperty> roots) {
        return index(roots).flattened().stream();
    }

    static Stream<ConfigProperty> stream(ConfigProperty root) {
        return Stream.concat(Stream.of(root), stream(root.children()));
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
        return INDEXES.getUnchecked(roots);
    }

    private static Index buildIndex(List<ConfigProperty> roots) {
        List<ConfigProperty> flattened = new ArrayList<>();
        Map<String, ConfigProperty> byPath = new LinkedHashMap<>();
        List<ConfigProperty> synced = new ArrayList<>();

        Deque<ConfigProperty> pending = new ArrayDeque<>(roots.size());
        pushChildren(roots, pending);
        while (!pending.isEmpty()) {
            ConfigProperty property = pending.removeFirst();
            pushChildren(property.children(), pending);
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
