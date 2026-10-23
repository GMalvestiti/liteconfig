package com.gmalvestiti.minecraft.liteconfig.api;

import com.gmalvestiti.minecraft.liteconfig.api.annotations.Config;
import com.gmalvestiti.minecraft.liteconfig.exception.LiteConfigException;
import com.gmalvestiti.minecraft.liteconfig.context.ConfigSettings;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigError;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigScope;
import com.gmalvestiti.minecraft.liteconfig.platform.Platform;
import com.gmalvestiti.minecraft.liteconfig.registry.ConfigRegistry;
import com.gmalvestiti.minecraft.liteconfig.registry.RegisteredConfig;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Collects the caller's choices and creates the holder.
 *
 * <p>Obtain one from {@link LiteConfig#holder(Class)}, set {@link #modId(String)}, then finish
 * with {@link #create()}. Declare {@link Config#readOnly()} on the config class to refuse
 * explicit loads and updates through every holder.
 *
 * <p>Everything after the required mod id is optional. Omit {@code baseDir} to use the platform
 * config directory; passing {@code null} to an event method registers nothing:
 *
 * <pre>{@code
 * // Defaults: FALLBACK policies, platform config directory.
 * ConfigHolder<MyModConfig> config = LiteConfig.holder(MyModConfig.class)
 *     .modId("mymod")
 *     .create();
 *
 * // Instance config directory, reacting to changes.
 * ConfigHolder<MyModConfig> config = LiteConfig.holder(MyModConfig.class)
 *     .modId("mymod")
 *     .baseDir(Platform.getConfigDir()) // config/mymod/<@Config.name()>.json5
 *     .onUpdate(state -> LOGGER.info("HUD scale: {}", state.hudScale))
 *     .create();
 * }</pre>
 *
 * <p>The default base directory resolves to ./config unless overridden when
 * {@link #create()} is called. File format, failure policies, and state cloning belong to the
 * config class.
 *
 * <p>Creating a locally active config loads persisted state and writes it back on the first
 * build. Inactive configs expose declared defaults, without loading another world's settings.
 * A world-owned config becomes locally active
 * when its world loads. Instance-owned configs are registered
 * once per root directory and owning mod id; world-owned configs share one registration across
 * world switches regardless of the builder's base directory. Building another holder for that
 * registration returns the same holder instance, without re-reading the file. Its mod id must
 * match the existing registration. Read-only behavior, subscriptions, and closed state belong to that shared
 * registration: closing any holder invalidates all handles and removes all events.
 *
 * @param <T> the root config type
 */
public final class ConfigBuilder<T> {

    private final Class<T> type;
    private final List<Consumer<ConfigHolder<T>>> eventRegistrations = new ArrayList<>();
    private String modId;
    private Path baseDirectory;

    ConfigBuilder(Class<T> type) {
        if (type == null) {
            throw ConfigScope.unknown().exception(ConfigError.BUILD_REQUIRED_ARGUMENT, "type");
        }
        this.type = type;
    }

    /**
     * Sets the owning mod id used in failure messages and logging.
     *
     * <p>Required. The id becomes the config scope, prefixes every failure message as
     * {@code [modId]}, and names the logger.
     *
     * <p>It also scopes the resolved file path unless {@link Config#modIdDirectory()} is disabled.
     *
     * @param modId the owning mod id; must not be {@code null} or blank
     * @return this builder
     * @throws LiteConfigException with {@link ConfigError#BUILD_REQUIRED_ARGUMENT} if {@code modId} is {@code null} or blank
     */
    public ConfigBuilder<T> modId(String modId) {
        this.modId = required(modId, "modId");
        return this;
    }

    /**
     * Overrides the directory that contains this mod's config files.
     *
     * <p>Parsed with {@link Path#of(String, String...)}, then made absolute and normalized.
     * Directories only — the file name always comes from {@link Config#name()}. Prefer
     * {@link #baseDir(Path)} when a {@link Path} is already in hand.
     *
     * <pre>{@code
     * // with .modId("mymod")
     * .baseDir("config") // config/mymod/<@Config.path() dirs>/<@Config.name()>.json5
     * }</pre>
     *
     * @param baseDir the directory string; must not be {@code null} or blank
     * @return this builder
     * @throws LiteConfigException with {@link ConfigError#BUILD_REQUIRED_ARGUMENT} if {@code baseDir} is {@code null} or blank
     * @throws LiteConfigException with {@link ConfigError#INVALID_CONFIG_PATH} if {@code baseDir} cannot be parsed as a path
     * @see #baseDir(Path)
     */
    public ConfigBuilder<T> baseDir(String baseDir) {
        String value = required(baseDir, "baseDir");
        try {
            return baseDir(Path.of(value));
        } catch (InvalidPathException ex) {
            throw scope().exception(ConfigError.INVALID_CONFIG_PATH, ex, type.getName(), value);
        }
    }

    /**
     * Overrides the directory that contains this mod's config files.
     *
     * <p>Made absolute and normalized when set, so a later change to the process working
     * directory cannot move the config root. Omit this to use {@link Platform#getConfigDir()},
     * which is {@code config/} in a normal game install. The {@link Config#path()} directories
     * are appended inside the owning mod's directory unless mod-id scoping is disabled.
     * {@link ConfigType#WORLD} uses the active world directory instead:
     *
     * <pre>{@code
     * // with @Config(name = "mymod") and .modId("mymod")
     * .baseDir(Platform.getConfigDir()) // config/mymod/mymod.json5
     * .baseDir(tempDir)                 // isolated installation root, useful in tests
     * }</pre>
     *
     * <p>Unlike {@link #baseDir(String)}, this overload accepts a {@link Path} that is already
     * parsed, so it cannot throw {@link ConfigError#INVALID_CONFIG_PATH}.
     *
     * @param baseDir the directory; must not be {@code null}
     * @return this builder
     * @throws LiteConfigException with {@link ConfigError#BUILD_REQUIRED_ARGUMENT} if {@code baseDir} is {@code null}
     * @see #baseDir(String)
     */
    public ConfigBuilder<T> baseDir(Path baseDir) {
        if (baseDir == null) {
            throw scope().exception(ConfigError.BUILD_REQUIRED_ARGUMENT, "baseDir");
        }
        this.baseDirectory = baseDir.toAbsolutePath().normalize();
        return this;
    }

    /**
     * Registers a synchronous event after an accepted
     * {@link ConfigHolder#update} or {@link ConfigHolder#updateAndSave}.
     *
     * <pre>{@code
     * LiteConfig.holder(MyModConfig.class)
     *     .modId("mymod")
     *     .onUpdate(config -> LOGGER.info("HUD scale: {}", config.hudScale))
     *     .onUpdate(config -> LOGGER.info("config updated"))
     *     .create();
     * }</pre>
     *
     * <p>Events run on the initiating caller after the new state is already visible through
     * {@link ConfigHolder#data()}, in registration order. A rejected update fires nothing.
     * The same event contract applies to all events — see {@link #onLoad} for the full rules.
     *
     * @param listener event listener, or {@code null} to register nothing
     * @return this builder
     */
    public ConfigBuilder<T> onUpdate(Consumer<T> listener) {
        if (listener != null) {
            eventRegistrations.add(holder -> holder.onUpdate(listener));
        }
        return this;
    }

    /**
     * Registers a synchronous event on the initiating caller after a successful
     * {@link ConfigHolder#load} or local WORLD activation.
     *
     * <p>Does not fire on a load that fell back to defaults due to a read failure, or on the
     * build-time load that {@link #create()} performs during construction.
     *
     * <p><b>Event contract (applies to all events):</b>
     * <ul>
     *   <li>Receives the published state — the same instance {@code data()} returns at that
     *       moment. Read it; do not mutate it; do not keep the reference. Call
     *       {@link ConfigHolder#copy()} if you need to hold on to values.</li>
     *   <li>An event that throws is logged as {@link ConfigError#CHANGE_LISTENER_FAILED}
     *       and skipped. It cannot fail the triggering operation or stop later events.</li>
     * </ul>
     *
     * @param listener event listener, or {@code null} to register nothing
     * @return this builder
     */
    public ConfigBuilder<T> onLoad(Consumer<T> listener) {
        if (listener != null) {
            eventRegistrations.add(holder -> holder.onLoad(listener));
        }
        return this;
    }

    /**
     * Registers a synchronous event on the initiating caller after a successful
     * {@link ConfigHolder#save} or {@link ConfigHolder#updateAndSave}.
     *
     * <p>The same event contract described on {@link #onLoad} applies here.
     *
     * @param listener event listener, or {@code null} to register nothing
     * @return this builder
     */
    public ConfigBuilder<T> onSave(Consumer<T> listener) {
        if (listener != null) {
            eventRegistrations.add(holder -> holder.onSave(listener));
        }
        return this;
    }

    /**
     * Returns the single holder owned by this registration, creating it when needed.
     *
     * <p>Building a locally active config touches disk on its first build. It resolves file paths,
     * rejects invalid config models, registers extension validators, and validates defaults;
     * then it reads what is on disk and writes the accepted state back. The config file exists
     * once this returns, and the holder starts in sync with disk. A first run gets its
     * defaults written out; an existing valid file seeds the holder; an invalid one is moved
     * aside under a fallback read policy and replaced with defaults. Building the same config
     * again returns that same holder instance and state. Inactive configs
     * instead expose validated defaults without accessing a file.
     *
     * <p>Closing the holder retires its registration. A later build creates a new holder;
     * references to the old holder remain closed. Events configured on this builder are
     * registered on each call, even when the holder already exists.
     *
     * @return a ready-to-use holder for {@code T}; never {@code null}
     * @throws LiteConfigException if the required
     *         mod id is missing, the model violates LiteConfig rules, two config types resolve to
     *         one file, defaults fail validation, or a strict read or write policy refuses the
     *         build-time load or write
     */
    public ConfigHolder<T> create() {
        RegisteredConfig<T> registration = ConfigRegistry.register(settings());
        ConfigHolder<T> holder = registration.access();

        try {
            eventRegistrations.forEach(register -> register.accept(holder));
            return holder;
        } catch (RuntimeException | Error failure) {
            holder.close();
            throw failure;
        }
    }

    /**
     * Resolves defaults into the immutable settings this builder describes.
     *
     * @return resolved settings; never {@code null}
     * @throws LiteConfigException with {@link ConfigError#BUILD_REQUIRED_ARGUMENT} if {@link #modId(String)} was never given a non-blank value
     */
    ConfigSettings<T> settings() {
        this.modId = required(modId, "modId");
        return new ConfigSettings<>(
            type,
            scope(),
            Objects.requireNonNullElseGet(baseDirectory, Platform::getConfigDir)
        );
    }

    private ConfigScope scope() {
        return modId == null ? ConfigScope.unknown() : new ConfigScope(modId);
    }

    private String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw scope().exception(ConfigError.BUILD_REQUIRED_ARGUMENT, name);
        }
        return value;
    }
}
