package com.gmalvestiti.minecraft.liteconfig.shared;

import java.util.Objects;
import java.util.regex.Pattern;

public final class PropertyPath {

    private static final String SEPARATOR = ".";
    private static final Pattern SEPARATOR_PATTERN = Pattern.compile(Pattern.quote(SEPARATOR));

    private PropertyPath() {}

    public static String[] split(String path) {
        Objects.requireNonNull(path, "path");

        if (path.isBlank()) {
            throw new IllegalArgumentException("config path must not be blank");
        }

        String[] steps = SEPARATOR_PATTERN.split(path, -1);
        for (String step : steps) {
            if (step.isBlank()) {
                throw new IllegalArgumentException("config path must not contain blank segments");
            }
        }

        return steps;
    }

    public static String child(String parent, String key) {
        Objects.requireNonNull(key, "key");

        if (key.isBlank()) {
            throw new IllegalArgumentException("config key must not be blank");
        }

        if (key.contains(SEPARATOR)) {
            throw new IllegalArgumentException("config key must not contain the path separator");
        }

        if (parent == null || parent.isEmpty()) {
            return key;
        }

        split(parent);

        return parent + SEPARATOR + key;
    }

    public static String last(String[] steps) {
        Objects.requireNonNull(steps, "steps");

        if (steps.length == 0) {
            throw new IllegalArgumentException("config path must contain at least one step");
        }

        String last = Objects.requireNonNull(steps[steps.length - 1], "last step");
        if (last.isBlank()) {
            throw new IllegalArgumentException("last step must not be blank");
        }

        return last;
    }
}
