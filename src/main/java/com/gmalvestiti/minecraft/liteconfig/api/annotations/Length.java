package com.gmalvestiti.minecraft.liteconfig.api.annotations;

import com.gmalvestiti.minecraft.liteconfig.api.metadata.ConfigMetadata;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Bounds how many elements a field may hold.
 *
 * <p>Counts UTF-16 code units on a string, entries on a collection or map, and elements on an
 * array. Both ends are inclusive, and either may be left open:
 *
 * <pre>{@code
 * @Config(name = "mymod")
 * public final class MyModConfig {
 *
 *     @Length(max = 32)
 *     public String profileName = "default";
 *
 *     @Length(min = 1)
 *     public List<String> enabledFeatures = List.of("hud");
 * }
 * }</pre>
 *
 * <p>Violations are reported under the id {@code length.<path>}, using the property's dotted
 * persisted path. The bounds are published on {@link ConfigMetadata}.
 *
 * <p>Bounds that cannot be satisfied — a negative {@code min}, or a {@code min} above the
 * {@code max} — are rejected when the holder is built.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Length {

    /**
     * The smallest accepted number of elements, inclusive.
     *
     * @return the lower bound; must not be negative
     */
    int min() default 0;

    /**
     * The largest accepted number of elements, inclusive.
     *
     * @return the upper bound, or {@link Integer#MAX_VALUE} to leave it open
     */
    int max() default Integer.MAX_VALUE;
}
