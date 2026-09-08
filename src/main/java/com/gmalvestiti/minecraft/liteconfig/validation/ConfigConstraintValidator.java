package com.gmalvestiti.minecraft.liteconfig.validation;

import com.gmalvestiti.minecraft.liteconfig.api.metadata.ConfigConstraints;
import com.gmalvestiti.minecraft.liteconfig.api.metadata.ConfigMetadata;
import com.gmalvestiti.minecraft.liteconfig.api.metadata.ConfigProperty;
import com.gmalvestiti.minecraft.liteconfig.api.spi.Violation;
import com.gmalvestiti.minecraft.liteconfig.metadata.ConfigFieldPlan;
import com.gmalvestiti.minecraft.liteconfig.reflection.ConfigFieldAccess;
import com.gmalvestiti.minecraft.liteconfig.metadata.DeclaredConfig;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class ConfigConstraintValidator {

    private final ConfigFieldAccess fieldAccess;
    private final ConfigFieldPlan fields;
    private final Set<String> requiredChoices;
    private final Set<String> requiredObjects;
    private final boolean metadataAware;

    public ConfigConstraintValidator(ConfigFieldAccess fieldAccess) {
        this(fieldAccess, ConfigFieldPlan.of(Object.class), null);
    }

    public ConfigConstraintValidator(
        ConfigFieldAccess fieldAccess,
        ConfigFieldPlan fields,
        ConfigMetadata metadata
    ) {
        this.fieldAccess = fieldAccess;
        this.fields = fields;
        this.metadataAware = metadata != null;
        this.requiredChoices = metadata == null
            ? Set.of()
            : metadata.flatten()
                .filter(property -> !property.constraints().allowedValues().isEmpty())
                .filter(property -> property.defaultValue() != null)
                .map(ConfigProperty::path)
                .collect(Collectors.toUnmodifiableSet());
        this.requiredObjects = metadata == null
            ? Set.of()
            : requiredObjectsOf(metadata);
    }

    public List<Violation> run(Object candidate) {
        if (candidate == null) {
            return List.of();
        }

        List<Violation> violations = new ArrayList<>();
        collect(candidate, "", Collections.newSetFromMap(new IdentityHashMap<>()), violations);

        return List.copyOf(violations);
    }

    private void collect(Object owner, String prefix, Set<Object> ancestors, List<Violation> violations) {
        if (!ancestors.add(owner)) {
            return;
        }

        try {
            for (DeclaredConfig.Property property : fields.properties(owner.getClass())) {
                Object value = fieldAccess.read(property.field(), owner);
                String path = prefix.isEmpty() ? property.key() : prefix + "." + property.key();

                check(property, value, path, violations);

                boolean descendable = fields.descendable(property);
                if (descendable) {
                    if (value == null && requiredObjects.contains(path)) {
                        violations.add(Violation.of("required." + path, "%s must not be null".formatted(path)));
                    } else if (value != null) {
                        collect(value, path, ancestors, violations);
                    }
                }
            }
        } finally {
            ancestors.remove(owner);
        }
    }

    private void check(DeclaredConfig.Property property, Object value, String path, List<Violation> violations) {
        ConfigConstraints constraints = property.constraints();
        if (constraints.isEmpty()) {
            return;
        }

        if (value == null) {
            checkMissingChoice(property, path, violations);
            return;
        }

        if (constraints.hasRange()) {
            checkRange(constraints, value, path, violations);
        }

        if (constraints.hasPattern()) {
            checkPattern(constraints, value, path, violations);
        }

        if (constraints.hasLength()) {
            checkLength(constraints, value, path, violations);
        }

        checkAllowedValues(constraints, value, path, violations);
    }

    private void checkRange(ConfigConstraints constraints, Object value, String path, List<Violation> violations) {
        if (!(value instanceof Number number)) {
            return;
        }

        double actual = number.doubleValue();
        if (Double.isNaN(actual)) {
            violations.add(Violation.of("range." + path, "%s must be a number, was NaN".formatted(path)));
            return;
        }

        OptionalDouble min = constraints.min();
        if (min.isPresent() && actual < min.getAsDouble()) {
            violations.add(Violation.of("range." + path, "%s must be at least %s, was %s".formatted(path, min.getAsDouble(), value)));
        }

        OptionalDouble max = constraints.max();
        if (max.isPresent() && actual > max.getAsDouble()) {
            violations.add(Violation.of("range." + path, "%s must be at most %s, was %s".formatted(path, max.getAsDouble(), value)));
        }
    }

    private void checkPattern(ConfigConstraints constraints, Object value, String path, List<Violation> violations) {
        if (!(value instanceof String text)) {
            return;
        }

        Optional<Pattern> pattern = constraints.pattern();
        if (pattern.isPresent() && !pattern.get().matcher(text).matches()) {
            violations.add(Violation.of("pattern." + path, "%s must match %s, was '%s'".formatted(path, pattern.get().pattern(), text)));
        }
    }

    private void checkLength(ConfigConstraints constraints, Object value, String path, List<Violation> violations) {
        int length = switch (value) {
            case String text -> text.length();
            case Collection<?> items -> items.size();
            case Map<?, ?> entries -> entries.size();
            default -> value.getClass().isArray() ? Array.getLength(value) : -1;
        };

        if (length < 0) {
            return;
        }

        OptionalInt min = constraints.minLength();
        if (min.isPresent() && length < min.getAsInt()) {
            violations.add(Violation.of("length." + path, "%s must hold at least %d, held %d".formatted(path, min.getAsInt(), length)));
        }

        OptionalInt max = constraints.maxLength();
        if (max.isPresent() && length > max.getAsInt()) {
            violations.add(Violation.of("length." + path, "%s must hold at most %d, held %d".formatted(path, max.getAsInt(), length)));
        }
    }

    private void checkAllowedValues(ConfigConstraints constraints, Object value, String path, List<Violation> violations) {
        if (!(value instanceof String text) || constraints.allowedValues().isEmpty()) {
            return;
        }

        boolean allowed = constraints.allowedValues().stream().anyMatch(allowedValue -> allowedValue.equalsIgnoreCase(text));
        if (!allowed) {
            violations.add(Violation.of(
                "value." + path,
                "%s must be one of %s, was '%s'".formatted(
                    path,
                    String.join(", ", constraints.allowedValues()),
                    text
                )
            ));
        }
    }

    /**
     * A field restricted to a fixed set of values reads back as {@code null} when the file names
     * one that does not exist. Metadata-aware validation preserves a deliberately {@code null}
     * default while still rejecting an unknown persisted constant.
     */
    private void checkMissingChoice(DeclaredConfig.Property described, String path, List<Violation> violations) {
        List<String> allowed = described.constraints().allowedValues();
        if (allowed.isEmpty()
            || (metadataAware && !requiredChoices.contains(path))) {
            return;
        }

        violations.add(Violation.of("value." + path, "%s must be one of %s".formatted(path, String.join(", ", allowed))));
    }

    private static Set<String> requiredObjectsOf(ConfigMetadata metadata) {
        Set<String> required = new LinkedHashSet<>();

        for (ConfigProperty property : metadata.synced()) {

            String path = property.path();
            int separator = path.indexOf('.');

            while (separator >= 0) {
                required.add(path.substring(0, separator));
                separator = path.indexOf('.', separator + 1);
            }
        }

        return Set.copyOf(required);
    }
}
