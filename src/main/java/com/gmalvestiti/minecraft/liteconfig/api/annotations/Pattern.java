package com.gmalvestiti.minecraft.liteconfig.api.annotations;

import com.gmalvestiti.minecraft.liteconfig.api.metadata.ConfigMetadata;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Requires a string field to match a regular expression.
 *
 * <p>The pattern must match the value in full, so anchors are unnecessary:
 *
 * <pre>{@code
 * @Config(name = "mymod")
 * public final class MyModConfig {
 *
 *     @Pattern("[a-z0-9_]+")
 *     public String profileName = "default";
 *
 *     @Pattern("#[0-9a-fA-F]{6}")
 *     public String accentColor = "#33aaff";
 * }
 * }</pre>
 *
 * <p>Violations are reported under the id {@code pattern.<field>}. The pattern is published on
 * {@link ConfigMetadata} for editors that validate as the player types.
 *
 * <p>An expression that does not compile is rejected when the holder is built, rather than on
 * the first value that reaches it.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Pattern {

    /**
     * The regular expression the whole value must match.
     *
     * @return a {@link java.util.regex.Pattern} expression; must not be blank
     */
    String value();
}
