package com.gmalvestiti.minecraft.liteconfig.validation.check;

import com.gmalvestiti.minecraft.liteconfig.api.metadata.ConfigConstraints;
import com.gmalvestiti.minecraft.liteconfig.api.spi.Violation;

import java.lang.reflect.Array;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import java.util.regex.Pattern;

final class PropertyRules {

    private PropertyRules() {}

    static String path(String prefix, String key) {
        return prefix.isEmpty() ? key : prefix + "." + key;
    }

    static void check(
        ConfigConstraints constraints,
        String prefix,
        String key,
        Object value,
        Set<String> requiredChoices,
        List<Violation> violations
    ) {
        if (value == null) {
            checkMissing(constraints, prefix, key, requiredChoices, violations);
            return;
        }

        if (constraints.hasNotBlank() && value instanceof String text && text.isBlank()) {
            String path = path(prefix, key);
            violations.add(Violation.of("notblank." + path, "%s must not be blank".formatted(path)));
        }

        if (constraints.hasRange()) {
            checkRange(constraints, prefix, key, value, violations);
        }

        if (constraints.hasPattern()) {
            checkPattern(constraints, prefix, key, value, violations);
        }

        if (constraints.hasLength()) {
            checkLength(constraints, prefix, key, value, violations);
        }

        if (!constraints.allowedValues().isEmpty() && value instanceof String text) {
            checkAllowed(constraints, prefix, key, text, violations);
        }
    }

    private static void checkMissing(
        ConfigConstraints constraints,
        String prefix,
        String key,
        Set<String> requiredChoices,
        List<Violation> violations
    ) {
        boolean notNull = constraints.hasNotNull();
        boolean notBlank = constraints.hasNotBlank();
        boolean choice = !constraints.allowedValues().isEmpty();
        if (!notNull && !notBlank && !choice) {
            return;
        }

        String path = path(prefix, key);
        if (notNull) {
            violations.add(Violation.of("notnull." + path, "%s must not be null".formatted(path)));
        }

        if (notBlank) {
            violations.add(Violation.of("notblank." + path, "%s must not be blank".formatted(path)));
        }

        if (choice && requiredChoices.contains(path)) {
            violations.add(Violation.of(
                "value." + path,
                "%s must be one of %s".formatted(path, String.join(", ", constraints.allowedValues()))));
        }
    }

    private static void checkRange(
        ConfigConstraints constraints,
        String prefix,
        String key,
        Object value,
        List<Violation> violations
    ) {
        if (!(value instanceof Number number)) {
            return;
        }

        double actual = number.doubleValue();
        if (Double.isNaN(actual)) {
            String path = path(prefix, key);
            violations.add(Violation.of("range." + path, "%s must be a number, was NaN".formatted(path)));
            return;
        }

        OptionalDouble min = constraints.min();
        if (min.isPresent() && actual < min.getAsDouble()) {
            String path = path(prefix, key);
            violations.add(Violation.of("range." + path, "%s must be at least %s, was %s".formatted(path, min.getAsDouble(), value)));
        }

        OptionalDouble max = constraints.max();
        if (max.isPresent() && actual > max.getAsDouble()) {
            String path = path(prefix, key);
            violations.add(Violation.of("range." + path, "%s must be at most %s, was %s".formatted(path, max.getAsDouble(), value)));
        }
    }

    private static void checkPattern(
        ConfigConstraints constraints,
        String prefix,
        String key,
        Object value,
        List<Violation> violations
    ) {
        if (!(value instanceof String text)) {
            return;
        }

        Pattern pattern = constraints.pattern().orElse(null);
        if (pattern != null && !pattern.matcher(text).matches()) {
            String path = path(prefix, key);
            violations.add(Violation.of("pattern." + path, "%s must match %s, was '%s'".formatted(path, pattern.pattern(), text)));
        }
    }

    private static void checkLength(
        ConfigConstraints constraints,
        String prefix,
        String key,
        Object value,
        List<Violation> violations
    ) {
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
            String path = path(prefix, key);
            violations.add(Violation.of("length." + path, "%s must hold at least %d, held %d".formatted(path, min.getAsInt(), length)));
        }

        OptionalInt max = constraints.maxLength();
        if (max.isPresent() && length > max.getAsInt()) {
            String path = path(prefix, key);
            violations.add(Violation.of("length." + path, "%s must hold at most %d, held %d".formatted(path, max.getAsInt(), length)));
        }
    }

    private static void checkAllowed(
        ConfigConstraints constraints,
        String prefix,
        String key,
        String text,
        List<Violation> violations
    ) {
        for (String allowed : constraints.allowedValues()) {
            if (allowed.equalsIgnoreCase(text)) {
                return;
            }
        }

        String path = path(prefix, key);
        violations.add(Violation.of(
            "value." + path,
            "%s must be one of %s, was '%s'".formatted(path, String.join(", ", constraints.allowedValues()), text)));
    }
}
