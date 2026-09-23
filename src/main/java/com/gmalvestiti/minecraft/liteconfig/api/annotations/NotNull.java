package com.gmalvestiti.minecraft.liteconfig.api.annotations;

import com.gmalvestiti.minecraft.liteconfig.api.metadata.ConfigMetadata;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Requires a reference field to have a value.
 *
 * <p>Violations are reported under the id {@code notnull.<path>}, using the property's dotted
 * persisted path. The requirement is published on {@link ConfigMetadata}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface NotNull {
}
