package com.gmalvestiti.minecraft.liteconfig.api.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Restricts a string field to a fixed, case-insensitive set of values.
 *
 * <pre>{@code
 * @Config(name = "mymod")
 * public final class MyModConfig {
 *
 *     @AllowedValues({"mysql", "sqlite"})
 *     public String database = "sqlite";
 * }</pre>
 *
 * <p>Violations are reported under the id {@code value.<path>}, using the property's dotted
 * persisted path.
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
}
