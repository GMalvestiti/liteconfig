package com.gmalvestiti.minecraft.liteconfig.api.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Excludes a public field from config persistence.
 *
 * <p>By default, every non-static public field of a {@link Config @Config} class is written to
 * and read from the config file. Apply this annotation when a field must remain public — for
 * example, to satisfy an interface or allow mod-internal access — but should never appear in
 * the end file:
 *
 * <pre>{@code
 *
 * @Config(name = "mymod")
 * public final class MyModConfig {
 *
 *     public int hudScale = 2;
 *
 *     @Ignore
 *     public String sessionCache = ""; // public but not persisted
 * }
 * }</pre>
 *
 * <p>An ignored field behaves as if it did not exist from LiteConfig's point of view: it is
 * never written to the file, never read back, never commented, and never checked by the restart
 * guard. Its value is whatever the constructor sets it to after every load.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Ignore {
}
