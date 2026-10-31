package com.gmalvestiti.minecraft.liteconfig.metadata;

import com.gmalvestiti.minecraft.liteconfig.api.ConfigType;
import com.gmalvestiti.minecraft.liteconfig.api.RestartSyncPolicy;
import com.gmalvestiti.minecraft.liteconfig.api.SyncMode;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Config;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Entry;
import com.gmalvestiti.minecraft.liteconfig.api.metadata.ConfigConstraints;
import com.gmalvestiti.minecraft.liteconfig.reflection.PersistedFields;
import com.gmalvestiti.minecraft.liteconfig.storage.ConfigSerialization;

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
    private final SyncMode syncMode;
    private final ConfigType type;
    private final RestartSyncPolicy restartSyncPolicy;
    private final boolean readOnly;
    private final boolean modIdDirectory;
    private final List<Property> properties;
    private final Map<String, Property> byKey;
    private final List<KeyProblem> keyProblems;

    private DeclaredConfig(Class<?> type) {
        Config config = type.getAnnotation(Config.class);

        this.comment = config == null ? List.of() : lines(config.comment());
        this.version = config == null ? 0 : config.version();
        this.syncMode = config == null ? SyncMode.DISABLED : config.sync();
        this.type = config == null ? null : config.type();
        this.restartSyncPolicy = config == null ? RestartSyncPolicy.DEFER : config.restartSyncPolicy();
        this.readOnly = config != null && config.readOnly();
        this.modIdDirectory = config != null && config.modIdDirectory();

        Map<String, Property> described = new LinkedHashMap<>();
        List<KeyProblem> foundKeyProblems = new ArrayList<>();

        for (Field field : PersistedFields.of(type)) {
            String key = ConfigSerialization.propertyNameOf(field);
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
        return syncMode == SyncMode.ENABLED;
    }

    public SyncMode syncMode() {
        return syncMode;
    }

    public ConfigType type() {
        return type;
    }

    public RestartSyncPolicy restartSyncPolicy() {
        return restartSyncPolicy;
    }

    public boolean readOnly() {
        return readOnly;
    }

    public boolean modIdDirectory() {
        return modIdDirectory;
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

        DeclaredListener.Resolution listener = entry == null || entry.listener().isBlank()
            ? new DeclaredListener.Resolution(null, null)
            : DeclaredListener.resolve(field, entry.listener());

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
            entry == null ? SyncMode.INHERIT : entry.sync(),
            Optional.ofNullable(listener.method()),
            Optional.ofNullable(listener.problem()),
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
     * @param sync the field's declared selection, inherited when no {@link Entry} is present
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
        SyncMode sync,
        Optional<Method> listener,
        Optional<String> listenerProblem,
        ConfigConstraints constraints,
        List<String> problems
    ) {}
}
