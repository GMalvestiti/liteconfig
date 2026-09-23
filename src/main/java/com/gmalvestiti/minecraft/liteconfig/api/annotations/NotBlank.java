package com.gmalvestiti.minecraft.liteconfig.api.annotations;

import com.gmalvestiti.minecraft.liteconfig.api.metadata.ConfigMetadata;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Requires a string field to contain at least one non-whitespace character.
 *
 * <p>{@code null}, an empty string, and a whitespace-only string violate this constraint.
 * Violations are reported under the id {@code notblank.<path>}, using the property's dotted
 * persisted path. The requirement is published on {@link ConfigMetadata}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface NotBlank {
}
