package com.gmalvestiti.minecraft.liteconfig.api;

import com.gmalvestiti.minecraft.liteconfig.api.annotations.Config;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigError;
import com.gmalvestiti.minecraft.liteconfig.exception.LiteConfigException;
import com.gmalvestiti.minecraft.liteconfig.registry.ConfigCodecRegistry;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * The entry point to LiteConfig.
 *
 * <p>This class holds the process-wide codec registry, but no live config state. Call
 * {@link #holder(Class)}, set the mod id, and keep the returned {@link ConfigHolder} for the
 * lifetime of your mod. File access begins only when the builder creates a holder.
 *
 * <pre>{@code
 * @Config(name = "mymod")                      // -> config/mymod.json5
 * public final class MyModConfig {
 *     public boolean showHints = true;
 * }
 *
 * ConfigHolder<MyModConfig> config = LiteConfig.holder(MyModConfig.class)
 *     .modId("mymod")
 *     .create();                               // creates the file and loads it
 *
 * boolean hints = config.data().showHints;
 * }</pre>
 *
 * <p>Only {@link ConfigBuilder#modId(String)} is required. The platform config directory and
 * mutable access are builder defaults; file format, failure policies, and state cloning are
 * declared by {@link Config}. See {@link ConfigBuilder} for holder options and {@link Config}
 * for how the file path is resolved.
 *
 * <p>{@code create()} already loads, so {@link ConfigHolder#load()} is only needed to re-read a
 * file that changed after startup.
 *
 * @see ConfigBuilder
 * @see ConfigHolder
 */
public final class LiteConfig {

    private static final ConfigCodecRegistry CODEC_REGISTRY = new ConfigCodecRegistry();

    private LiteConfig() {}

    /**
     * Begins a holder build for one annotated root type.
     *
     *
     * <pre>{@code
     * ConfigHolder<ModConfigs> configs = LiteConfig.holder(ModConfigs.class)
     *     .modId("mymod")
     *     .create();
     * }</pre>
     *
     * @param type the root class annotated with {@link Config}; must not be {@code null}
     * @param <T> the root config type
     * @return a builder with defaults still unresolved; never {@code null}
     * @throws LiteConfigException with {@link ConfigError#BUILD_REQUIRED_ARGUMENT} if {@code type} is {@code null}
     */
    public static <T> ConfigBuilder<T> holder(Class<T> type) {
        return new ConfigBuilder<>(type);
    }

    /**
     * Registers codecs on the shared registry, then begins a holder build.
     *
     * <pre>{@code
     * ConfigHolder<ModConfigs> configs = LiteConfig.holder(ModConfigs.class, codecs -> codecs
     *     .registerCodec(IntRange.class, IntRange.CODEC)
     *     .registerStreamCodec(IntRange.class, IntRange.STREAM_CODEC))
     *     .modId("mymod")
     *     .create();
     * }</pre>
     *
     * <p>The codecs are not owned by or scoped to the returned builder or holder. When a custom
     * file codec rejects a value, LiteConfig falls back to reflective serialization for that use.
     *
     * @param type the root class annotated with {@link Config}; must not be {@code null}
     * @param codecRegistrations registrations to attempt before creating the builder; must not be
     *                           {@code null}
     * @param <T> the root config type
     * @return a builder with defaults still unresolved; never {@code null}
     */
    public static <T> ConfigBuilder<T> holder(
        Class<T> type,
        Consumer<ConfigCodecRegistry> codecRegistrations
    ) {
        Objects.requireNonNull(codecRegistrations, "codecRegistrations");
        ConfigBuilder<T> builder = holder(type);
        codecRegistrations.accept(CODEC_REGISTRY);
        return builder;
    }

    /**
     * Returns the process-wide registry for custom file and sync value codecs.
     *
     * <p>Register codecs during common initialization, before creating holders that contain their
     * types. A codec applies to JSON5, TOML, default state cloning, and synced fields through the
     * same exact type key.
     *
     * @return the shared codec registry; never {@code null}
     * @see ConfigCodecRegistry
     */
    public static ConfigCodecRegistry codecs() {
        return CODEC_REGISTRY;
    }
}
