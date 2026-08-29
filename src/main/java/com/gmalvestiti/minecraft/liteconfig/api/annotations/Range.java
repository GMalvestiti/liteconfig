package com.gmalvestiti.minecraft.liteconfig.api.annotations;

import com.gmalvestiti.minecraft.liteconfig.api.ConfigHolder;
import com.gmalvestiti.minecraft.liteconfig.api.metadata.ConfigMetadata;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Bounds a numeric field to a closed interval.
 *
 * <p>Applies to the primitive number types and their wrappers. Both ends are inclusive, and
 * either may be left open:
 *
 * <pre>{@code
 * @Config(name = "mymod")
 * public final class MyModConfig {
 *
 *     @Range(min = 1, max = 8)
 *     public int hudScale = 2;
 *
 *     @Range(min = 0)
 *     public double spawnChance = 0.25;
 * }
 * }</pre>
 *
 * <p>The bound is enforced wherever the value enters the holder — on load, on
 * {@link ConfigHolder#update}, and on {@link ConfigHolder#updateAndSave} — and reported as a
 * violation with the id {@code range.<field>}. It is also published on
 * {@link ConfigMetadata}, so a config screen can render the right slider without repeating the
 * numbers.
 *
 * <p>A range whose {@code min} exceeds its {@code max} can never be satisfied and is rejected
 * when the holder is built.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Range {

    /**
     * The smallest accepted value, inclusive.
     *
     * @return the lower bound, or {@link Double#NEGATIVE_INFINITY} to leave it open
     */
    double min() default Double.NEGATIVE_INFINITY;

    /**
     * The largest accepted value, inclusive.
     *
     * @return the upper bound, or {@link Double#POSITIVE_INFINITY} to leave it open
     */
    double max() default Double.POSITIVE_INFINITY;
}
