package com.gmalvestiti.minecraft.liteconfig.api.annotations;

import com.gmalvestiti.minecraft.liteconfig.api.EntryNormalization;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Locale;

/**
 * Restricts a string field to a fixed, case-insensitive set of values and normalizes its spelling.
 *
 * <pre>
 * {@code @Config(name = "mymod")}
 * public final class MyModConfig {
 *
 *     {@code @AllowedValues({"mysql", "sqlite"})}
 *     public String database = "sqlite";
 *
 *     {@code @AllowedValues(value = {"fast", "safe"}, normalize = EntryNormalization.UPPER_CASE)}
 *     public String mode = "SAFE";
 *
 *     {@code @AllowedValues(value = {"Custom"}, normalize = EntryNormalization.NONE, trim = false)}
 *     public String label = "Custom";
 * }
 * </pre>
 *
 * <p>Violations are reported under the id {@code value.<path>}, using the property's dotted
 * persisted path. Accepted values and declared choices are trimmed and lowercased by default.
 * Trimming uses {@link String#trim()} before case normalization with {@link Locale#ROOT}.
 * Both happen before validation and restart comparisons. {@link EntryNormalization#NONE}
 * preserves case; set {@link #trim()} to {@code false} to preserve surrounding whitespace too.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface AllowedValues {

    /**
     * The only accepted values, compared without regard to case.
     *
     * @return one or more distinct values
     */
    String[] value();

    /**
     * Case normalization applied to declared choices and accepted string values.
     *
     * @return the normalization policy, lower case by default
     */
    EntryNormalization normalize() default EntryNormalization.LOWER_CASE;

    /**
     * Removes leading and trailing characters with {@link String#trim()} before normalizing case.
     *
     * @return whether to trim declared choices and accepted values, {@code true} by default
     */
    boolean trim() default true;
}
