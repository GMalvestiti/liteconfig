package com.gmalvestiti.minecraft.liteconfig.api;

import java.util.Locale;

/**
 * Locale-independent case normalization for string entries.
 *
 * <p>For example, {@code @AllowedValues(value = {"fast", "safe"}, normalize = EntryNormalization.UPPER_CASE)}
 * stores accepted values in upper case. Trimming is controlled separately by {@code AllowedValues.trim}.
 */
public enum EntryNormalization {
    /** Converts strings to upper case using {@link Locale#ROOT}. */
    UPPER_CASE,
    /** Converts strings to lower case using {@link Locale#ROOT}. */
    LOWER_CASE,
    /** Preserves case; trimming can still be enabled separately. */
    NONE;

    /**
     * Applies this case policy and optionally trims the string before applying it.
     *
     * @param value the string, or {@code null}
     * @param trim whether to remove leading and trailing characters using {@link String#trim()}
     * @return the normalized string, or {@code null} when the input is null
     */
    public String apply(String value, boolean trim) {
        if (value == null) {
            return null;
        }

        String text = trim ? value.trim() : value;
        return switch (this) {
            case UPPER_CASE -> text.toUpperCase(Locale.ROOT);
            case LOWER_CASE -> text.toLowerCase(Locale.ROOT);
            case NONE -> text;
        };
    }
}
