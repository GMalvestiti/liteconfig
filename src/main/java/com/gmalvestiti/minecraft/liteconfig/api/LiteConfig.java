package com.gmalvestiti.minecraft.liteconfig.api;

import com.gmalvestiti.minecraft.liteconfig.api.annotations.Config;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigError;
import com.gmalvestiti.minecraft.liteconfig.exception.LiteConfigException;
import com.gmalvestiti.minecraft.liteconfig.registry.ConfigCodecRegistry;

/**
 * The entry point to LiteConfig.
 *
 * <p>A stateless factory: it holds no config data, performs no I/O, and never exposes a
 * runtime implementation class. Call {@link #holder(Class)}, set the mod id, and keep the
 * returned {@link ConfigHolder} for the lifetime of your mod.
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
 * <p>Only {@link ConfigBuilder#modId(String)} is required; everything else — the {@code ASYNC}
 * holder, {@code FALLBACK} policies, and the platform config directory — is defaulted. See
 * {@link ConfigBuilder} to override them and {@link Config} for how the file path is resolved.
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
     * <p>The returned builder is mutable and single-use. Nothing is validated and no file
     * is touched until {@link ConfigBuilder#create()}, which reads the persisted state and
     * writes it back.
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
