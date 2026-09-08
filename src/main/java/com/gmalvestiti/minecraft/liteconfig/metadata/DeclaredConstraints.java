package com.gmalvestiti.minecraft.liteconfig.metadata;

import com.gmalvestiti.minecraft.liteconfig.api.annotations.AllowedValues;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Length;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Pattern;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Range;
import com.gmalvestiti.minecraft.liteconfig.api.metadata.ConfigConstraints;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import java.util.regex.PatternSyntaxException;

final class DeclaredConstraints {

    private DeclaredConstraints() {}

    static ConfigConstraints of(Field field, List<String> problems) {
        Range range = field.getAnnotation(Range.class);
        Length length = field.getAnnotation(Length.class);
        Pattern pattern = field.getAnnotation(Pattern.class);
        AllowedValues allowedValues = field.getAnnotation(AllowedValues.class);

        verify(field, range, length, pattern, allowedValues, problems);

        return new ConfigConstraints(
            bound(range == null ? Double.NEGATIVE_INFINITY : range.min(), Double.NEGATIVE_INFINITY),
            bound(range == null ? Double.POSITIVE_INFINITY : range.max(), Double.POSITIVE_INFINITY),
            compile(pattern, problems),
            limit(length == null ? 0 : length.min(), 0),
            limit(length == null ? Integer.MAX_VALUE : length.max(), Integer.MAX_VALUE),
            allowedValuesOf(field, allowedValues),
            range != null,
            pattern != null,
            length != null
        );
    }

    private static void verify(
        Field field,
        Range range,
        Length length,
        Pattern pattern,
        AllowedValues allowedValues,
        List<String> problems
    ) {

        Class<?> type = field.getType();

        if (range != null) {
            if (!numeric(type)) {
                problems.add("@Range does not support " + type.getTypeName());
            }

            if (Double.isNaN(range.min()) || Double.isNaN(range.max())) {
                problems.add("@Range bounds cannot be NaN");
            }

            if (range.min() > range.max()) {
                problems.add("@Range min %s is above max %s".formatted(range.min(), range.max()));
            }
        }

        if (length != null) {
            if (!sized(type)) {
                problems.add("@Length does not support " + type.getTypeName());
            }

            if (length.min() < 0) {
                problems.add("@Length min %d is negative".formatted(length.min()));
            }

            if (length.min() > length.max()) {
                problems.add("@Length min %d is above max %d".formatted(length.min(), length.max()));
            }
        }

        if (pattern != null && type != String.class) {
            problems.add("@Pattern does not support " + type.getTypeName());
        }

        if (allowedValues != null) {
            if (type != String.class) {
                problems.add("@AllowedValues does not support " + type.getTypeName());
            }

            if (allowedValues.value().length == 0) {
                problems.add("@AllowedValues must declare at least one value");
            }

            Set<String> unique = new HashSet<>();
            for (String value : allowedValues.value()) {
                if (!unique.add(value.toLowerCase(Locale.ROOT))) {
                    problems.add("@AllowedValues values must be distinct ignoring case");
                    break;
                }
            }
        }
    }

    private static boolean numeric(Class<?> type) {
        if (type.isPrimitive()) {
            return type != boolean.class && type != char.class;
        }
        return Number.class.isAssignableFrom(type);
    }

    private static boolean sized(Class<?> type) {
        return type == String.class
            || type.isArray()
            || Collection.class.isAssignableFrom(type)
            || java.util.Map.class.isAssignableFrom(type);
    }

    private static Optional<java.util.regex.Pattern> compile(Pattern pattern, List<String> problems) {
        if (pattern == null) {
            return Optional.empty();
        }

        if (pattern.value().isBlank()) {
            problems.add("@Pattern expression is blank");
            return Optional.empty();
        }

        try {
            return Optional.of(java.util.regex.Pattern.compile(pattern.value()));
        } catch (PatternSyntaxException invalid) {
            problems.add("@Pattern expression '%s' does not compile".formatted(pattern.value()));
            return Optional.empty();
        }
    }

    private static List<String> allowedValuesOf(Field field, AllowedValues declared) {
        if (declared != null) {
            return List.of(declared.value());
        }

        if (!field.getType().isEnum()) {
            return List.of();
        }

        List<String> names = new ArrayList<>();
        for (Object constant : field.getType().getEnumConstants()) {
            names.add(((Enum<?>) constant).name());
        }

        return List.copyOf(names);
    }

    private static OptionalDouble bound(double declared, double open) {
        return declared == open ? OptionalDouble.empty() : OptionalDouble.of(declared);
    }

    private static OptionalInt limit(int declared, int open) {
        return declared == open ? OptionalInt.empty() : OptionalInt.of(declared);
    }
}
