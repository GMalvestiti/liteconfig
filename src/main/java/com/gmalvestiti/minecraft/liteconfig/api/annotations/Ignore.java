package com.gmalvestiti.minecraft.liteconfig.api.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Excludes an instance field from config persistence.
 *
 * <p>By default, every non-static, non-transient, non-synthetic instance field of a
 * {@link Config @Config} class is selected for persistence; final or reflectively inaccessible
 * fields then make holder creation fail. Apply this annotation to runtime-only state that should
 * never appear in the file:
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
 * guard. A newly loaded object starts with the value assigned by its constructor, after which
 * lifecycle code may still change it.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Ignore {
}
