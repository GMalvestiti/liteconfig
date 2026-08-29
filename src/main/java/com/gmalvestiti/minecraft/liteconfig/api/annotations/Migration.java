package com.gmalvestiti.minecraft.liteconfig.api.annotations;

import com.gmalvestiti.minecraft.liteconfig.api.migration.ConfigData;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method that brings this config's file up from one version to the next.
 *
 * <p>Migrations live on the config class itself, next to the {@link Config#version()} they
 * exist for. The method must be {@code static}, return {@code void}, and take a single
 * {@link ConfigData}:
 *
 * <pre>{@code
 * @Config(name = "mymod", version = 3)
 * public final class MyModConfig {
 *     public Hud hud = new Hud();
 *
 *     @Migration(from = 1)
 *     static void toVersion2(ConfigData data) {
 *         data.rename("hudScale", "hud.scale");
 *     }
 *
 *     @Migration(from = 2)
 *     static void toVersion3(ConfigData data) {
 *         data.remove("legacyRenderer");
 *     }
 * }
 * }</pre>
 *
 * <p>A file at version 1 runs both steps in order; a file already at version 3 runs neither.
 * Every boundary below {@link Config#version()} needs a step, and a gap is reported as
 * {@code ConfigError.MISSING_CONFIG_MIGRATION}.
 *
 * <p>The method name is yours to choose; only {@link #from()} decides when it runs.
 *
 * <p>Migration methods may run on LiteConfig's asynchronous worker. They must be deterministic
 * data transformations and must not access client-only classes, worlds, entities, registries, or
 * other game state that is confined to the main thread.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Migration {

    /**
     * The version this step upgrades from, producing {@code from + 1}.
     *
     * <p>At least {@code 1}, since a file without a stored version is read as version 1.
     * Two steps declaring the same value are rejected as
     * {@code ConfigError.INVALID_MIGRATION}.
     *
     * @return the version this step starts at
     */
    int from();
}
