package com.gmalvestiti.minecraft.liteconfig.api.metadata;

import com.gmalvestiti.minecraft.liteconfig.api.annotations.Config;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Entry;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * One value in a config file, described without reading it.
 *
 * <p>A property knows where it lives in the file, what type it is, what it defaults to, and what
 * it will accept. That is everything a config screen needs to render a control:
 *
 * <pre>{@code
 * for (ConfigProperty property : metadata.flatten().toList()) {
 *     if (property.children().isEmpty()) {
 *         addControl(property.path(), property.type(), property.defaultValue(), property.constraints());
 *     }
 * }
 * }</pre>
 *
 * <p>Nested config objects are properties too: they carry their own {@link #children()} and no
 * meaningful constraints of their own.
 *
 * @param path dotted location in the file, such as {@code "hud.scale"}; unique within a metadata tree
 * @param key the key this property is written under, without its parents
 * @param fieldName the Java field name, which differs from {@link #key()} when
 *                  {@link Entry#name()} renames it
 * @param type the declared Java type
 * @param defaultValue the value a fresh config starts with; {@code null} for nested object
 *                     properties and fields that default to {@code null}
 * @param comment the lines declared with {@link Entry#comment()}, empty when undocumented
 * @param translationKey the key from {@link Entry#translationKey()}, empty when unset
 * @param restart whether {@link Entry#restart()} defers changes until the ownership scope restarts
 * @param sync whether this value travels from the server to connected clients, resolved from
 *             {@link Config#sync()} and {@link Entry#sync()}; a disabled enclosing section
 *             overrides inherited or explicitly enabled sync; {@code false} unless something
 *             asked for it
 * @param constraints the rules this value must satisfy; {@link ConfigConstraints#isEmpty()} when free
 * @param children the properties of a nested config object, empty for a plain value
 */
public record ConfigProperty(
    String path,
    String key,
    String fieldName,
    Class<?> type,
    Object defaultValue,
    List<String> comment,
    Optional<String> translationKey,
    boolean restart,
    boolean sync,
    ConfigConstraints constraints,
    List<ConfigProperty> children
) {

    public ConfigProperty {
        defaultValue = immutableDefault(defaultValue);
        comment = List.copyOf(comment);
        children = List.copyOf(children);
    }

    /**
     * Returns this property's declared default.
     *
     * <p>Collection and map defaults are recursively wrapped in immutable snapshots. An array is
     * copied on each call, so callers may edit that copy without changing the metadata. Mutable
     * custom-codec values remain shared references and must be treated as read-only or copied
     * before preparing an editable value:
     *
     * <pre>{@code
     * List&lt;String&gt; defaults = new ArrayList&lt;&gt;(
     *     (List&lt;String&gt;) property.defaultValue());
     * defaults.add("preview");
     * }</pre>
     *
     * @return the declared leaf default, or {@code null}
     */
    @Override
    public Object defaultValue() {
        return immutableDefault(defaultValue);
    }

    /**
     * Returns this property followed by every property nested under it, depth first.
     *
     * @return a stream that always starts with {@code this}; never {@code null}
     */
    public Stream<ConfigProperty> flatten() {
        return MetadataTraversal.stream(this);
    }

    private static Object immutableDefault(Object value) {
        if (value == null) {
            return null;
        }

        if (value.getClass().isArray()) {
            return immutableArray(value);
        }

        switch (value) {
            case List<?> values -> {
                List<Object> copy = new ArrayList<>(values.size());
                values.forEach(element -> copy.add(immutableDefault(element)));
                return Collections.unmodifiableList(copy);
            }
            case Set<?> values -> {
                Set<Object> copy = new LinkedHashSet<>(values.size());
                values.forEach(element -> copy.add(immutableDefault(element)));
                return Collections.unmodifiableSet(copy);
            }
            case Map<?, ?> values -> {
                Map<Object, Object> copy = new LinkedHashMap<>(values.size());
                values.forEach((key, element) ->
                    copy.put(immutableDefault(key), immutableDefault(element)));
                return Collections.unmodifiableMap(copy);
            }
            case Collection<?> values -> {
                List<Object> copy = new ArrayList<>(values.size());
                values.forEach(element -> copy.add(immutableDefault(element)));
                return Collections.unmodifiableCollection(copy);
            }
            default -> {}
        }

        return value;
    }

    private static Object immutableArray(Object source) {
        int length = Array.getLength(source);
        Object copy = Array.newInstance(source.getClass().getComponentType(), length);

        for (int index = 0; index < length; index++) {
            Object value = immutableDefault(Array.get(source, index));
            Class<?> component = source.getClass().getComponentType();

            if (value != null && !component.isInstance(value)) {
                switch (value) {
                    case List<?> list when component.isAssignableFrom(ArrayList.class) -> value = new ArrayList<>(list);
                    case Set<?> set when component.isAssignableFrom(LinkedHashSet.class) -> value = new LinkedHashSet<>(set);
                    case Map<?, ?> map when component.isAssignableFrom(LinkedHashMap.class) -> value = new LinkedHashMap<>(map);
                    default -> {}
                }
            }

            Array.set(copy, index, value);
        }

        return copy;
    }
}
