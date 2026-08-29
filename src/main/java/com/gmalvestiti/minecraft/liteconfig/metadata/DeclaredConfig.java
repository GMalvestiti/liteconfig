package com.gmalvestiti.minecraft.liteconfig.metadata;

import com.gmalvestiti.minecraft.liteconfig.api.annotations.Config;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Entry;
import com.gmalvestiti.minecraft.liteconfig.api.metadata.ConfigConstraints;
import com.gmalvestiti.minecraft.liteconfig.reflection.PersistedFields;
import com.gmalvestiti.minecraft.liteconfig.storage.ConfigBinder;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class DeclaredConfig {

    private static final ClassValue<DeclaredConfig> CACHE = new ClassValue<>() {
        @Override
        protected DeclaredConfig computeValue(Class<?> type) {
            return new DeclaredConfig(type);
        }
    };

    private final List<String> comment;
    private final int version;
    private final boolean sync;
    private final List<Property> properties;
    private final Map<String, Property> byKey;
    private final List<KeyProblem> keyProblems;

    private DeclaredConfig(Class<?> type) {
        Config config = type.getAnnotation(Config.class);

        this.comment = config == null ? List.of() : lines(config.comment());
        this.version = config == null ? 0 : config.version();
        this.sync = config != null && config.sync();

        Map<String, Property> described = new LinkedHashMap<>();
        List<KeyProblem> foundKeyProblems = new ArrayList<>();

        for (Field field : PersistedFields.of(type)) {
            String key = ConfigBinder.propertyNameOf(field);
            Property property = propertyOf(key, field);

            if (key.contains(".")) {
                foundKeyProblems.add(new InvalidKey(field, key));
            }

            Property previous = described.putIfAbsent(key, property);

            if (previous != null) {
                foundKeyProblems.add(new DuplicateKey(previous.field(), field, key));
            }
        }

        this.byKey = described;
        this.properties = List.copyOf(byKey.values());
        this.keyProblems = List.copyOf(foundKeyProblems);
    }

    public static DeclaredConfig of(Class<?> type) {
        return CACHE.get(Objects.requireNonNull(type, "type"));
    }

    public List<String> comment() {
        return comment;
    }

    public int version() {
        return version;
    }

    public boolean sync() {
        return sync;
    }

    public List<Property> properties() {
        return properties;
    }

    public Property property(String key) {
        return byKey.get(key);
    }

    public List<KeyProblem> keyProblems() {
        return keyProblems;
    }

    private static Property propertyOf(String key, Field field) {

        Entry entry = field.getAnnotation(Entry.class);
        List<String> problems = new ArrayList<>();

        DeclaredCallback.Resolution callback = entry == null || entry.callback().isBlank()
            ? new DeclaredCallback.Resolution(null, null)
            : DeclaredCallback.resolve(field, entry.callback());

        return new Property(
            key,
            field,
            field.getName(),
            field.getType(),
            field.getGenericType(),
            entry == null ? List.of() : lines(entry.comment()),
            entry == null || entry.translationKey().isBlank()
                ? Optional.empty()
                : Optional.of(entry.translationKey()),
            entry != null && entry.restart(),
            entry != null && entry.sync(),
            Optional.ofNullable(callback.method()),
            Optional.ofNullable(callback.problem()),
            DeclaredConstraints.of(field, problems),
            List.copyOf(problems)
        );
    }

    private static List<String> lines(String[] declared) {
        List<String> lines = new ArrayList<>();

        for (String block : declared) {
            lines.addAll(List.of(block.split("\\R", -1)));
        }

        return List.copyOf(lines);
    }

    public sealed interface KeyProblem permits InvalidKey, DuplicateKey {}

    public record InvalidKey(Field field, String key) implements KeyProblem {}

    public record DuplicateKey(Field first, Field second, String key) implements KeyProblem {}

    /**
     * One persisted field, as its annotations describe it.
     *
     * @param sync whether {@link Entry#sync()} asks for this field on its own; a field of a
     *             config that already syncs travels either way
     * @param problems annotation mistakes found while reading the field, empty when it is sound
     */
    public record Property(
        String key,
        Field field,
        String fieldName,
        Class<?> type,
        Type declaredType,
        List<String> comment,
        Optional<String> translationKey,
        boolean restart,
        boolean sync,
        Optional<Method> callback,
        Optional<String> callbackProblem,
        ConfigConstraints constraints,
        List<String> problems
    ) {
    }
}
