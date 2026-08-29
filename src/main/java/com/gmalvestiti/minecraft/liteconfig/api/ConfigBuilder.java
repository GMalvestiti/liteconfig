package com.gmalvestiti.minecraft.liteconfig.api;

import com.gmalvestiti.minecraft.liteconfig.api.annotations.Config;
import com.gmalvestiti.minecraft.liteconfig.exception.LiteConfigException;
import com.gmalvestiti.minecraft.liteconfig.context.ConfigSettings;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigError;
import com.gmalvestiti.minecraft.liteconfig.exception.ConfigScope;
import com.gmalvestiti.minecraft.liteconfig.holder.ConfigHolderImplementation;
import com.gmalvestiti.minecraft.liteconfig.network.ConfigSyncRegistry;
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
 * <p>Obtain one from {@link LiteConfig#holder(Class)}, set {@link #modId(String)}, optionally
 * choose {@link #readOnly()}, then finish with {@link #create()}:
 *
 * <ul>
 *   <li>{@link #create()} — a mutable holder with synchronous and asynchronous operations.</li>
 *   <li>{@code readOnly().create()} — a read-only handle that refuses mutation.</li>
 * </ul>
 *
 * <p>Everything after the required mod id is optional. Omit {@code baseDir} to use the platform
 * config directory; passing {@code null} to a listener method registers nothing:
 *
 * <pre>{@code
 * // Defaults: FALLBACK policies, platform config directory.
 * ConfigHolder<MyModConfig> config = LiteConfig.holder(MyModConfig.class)
 *     .modId("mymod")
 *     .create();
 *
 * // Custom directory, reacting to changes.
 * ConfigHolder<MyModConfig> config = LiteConfig.holder(MyModConfig.class)
 *     .modId("mymod")
 *     .baseDir(Platform.getConfigDir().resolve("mymod"))
 *     .onUpdate(ConfigSide.CLIENT, state -> hudRenderer.setScale(state.hudScale))
 *     .create();
 * }</pre>
 *
 * <p>Defaults resolve lazily when you call a create method: {@link Platform#getConfigDir()} and a
 * platform config directory. File format, failure policies, and state cloning belong to the
 * config class.
 *
 * <p>Creating touches disk the first time a config is built: it loads the persisted state and
 * writes it back, so the config files always exist afterwards and the holder starts in sync with
 * them. A config is registered once per root directory, so building a second holder for the same
 * class hands back another handle onto that registration — the two share one state, and the
 * second build neither re-reads the file nor revisits the options set here.
 *
 * @param <T> the root config type
 */
public final class ConfigBuilder<T> {

    private final Class<T> type;
    private final List<Consumer<ConfigHolder<T>>> listenerRegistrations = new ArrayList<>();
    private String modId;
    private Path baseDirectory;
    private boolean readOnly;

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
     * <p>It is <em>not</em> part of the resolved file path.
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
     * .baseDir("config/mymod")   // config/mymod/<@Config.path() dirs>/<@Config.name()>.json5
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
     * are then appended to it, and the {@link Config#name()} file inside those:
     *
     * <pre>{@code
     * // with @Config(name = "mymod")
     * .baseDir(Platform.getConfigDir().resolve("mymod"))   // config/mymod/mymod.json5
     * .baseDir(tempDir)                                    // isolated root, useful in tests
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
     * Registers a listener on the selected logical side's main thread after an accepted
     * {@link ConfigHolder#update} or {@link ConfigHolder#updateAndSave}.
     *
     * <pre>{@code
     * LiteConfig.holder(MyModConfig.class)
     *     .modId("mymod")
     *     .onUpdate(ConfigSide.CLIENT, config -> hudRenderer.setScale(config.hudScale))
     *     .onUpdate(ConfigSide.CLIENT, config -> LOGGER.info("config updated"))
     *     .create();
     * }</pre>
     *
     * <p>Listeners run after the new state is already visible through
     * {@link ConfigHolder#data()}, in registration order. A rejected update fires nothing.
     * The same listener contract applies to all hooks — see {@link #onLoad} for the full rules.
     *
     * <p>{@link ConfigSide#BOTH} registers one listener for each side.
     *
     * @param side     logical side that owns the listener; must not be {@code null}
     * @param listener callback, or {@code null} to register nothing
     * @return this builder
     */
    public ConfigBuilder<T> onUpdate(ConfigSide side, Consumer<T> listener) {
        Objects.requireNonNull(side, "side");
        if (listener != null) {
            listenerRegistrations.add(holder -> holder.onUpdate(side, listener));
        }
        return this;
    }

    /**
     * Registers a listener on the selected logical side's main thread after a successful
     * {@link ConfigHolder#load}.
     *
     * <p>Does not fire on a load that fell back to defaults due to a read failure, or on the
     * build-time load that {@link #create()} performs during construction.
     *
     * <p><b>Listener contract (applies to all hooks):</b>
     * <ul>
     *   <li>Receives the published state — the same instance {@code data()} returns at that
     *       moment. Read it; do not mutate it; do not keep the reference. Call
     *       {@link ConfigHolder#copy()} if you need to hold on to values.</li>
     *   <li>A listener that throws is logged as {@link ConfigError#CHANGE_LISTENER_FAILED}
     *       and skipped. It cannot fail the triggering operation or stop later listeners.</li>
     * </ul>
     *
     * <p>{@link ConfigSide#BOTH} registers one listener for each side.
     *
     * @param side     logical side that owns the listener; must not be {@code null}
     * @param listener callback, or {@code null} to register nothing
     * @return this builder
     */
    public ConfigBuilder<T> onLoad(ConfigSide side, Consumer<T> listener) {
        Objects.requireNonNull(side, "side");
        if (listener != null) {
            listenerRegistrations.add(holder -> holder.onLoad(side, listener));
        }
        return this;
    }

    /**
     * Registers a listener on the selected logical side's main thread after a successful
     * {@link ConfigHolder#save} or {@link ConfigHolder#updateAndSave}.
     *
     * <p>The same listener contract described on {@link #onLoad} applies here.
     *
     * <p>{@link ConfigSide#BOTH} registers one listener for each side.
     *
     * @param side     logical side that owns the listener; must not be {@code null}
     * @param listener callback, or {@code null} to register nothing
     * @return this builder
     */
    public ConfigBuilder<T> onSave(ConfigSide side, Consumer<T> listener) {
        Objects.requireNonNull(side, "side");
        if (listener != null) {
            listenerRegistrations.add(holder -> holder.onSave(side, listener));
        }
        return this;
    }

    /**
     * Configures the holder as read-only.
     *
     * <p>{@code update} is refused through the update failure policy, and {@code load} through
     * the read policy. They throw under {@link FailurePolicy#STRICT}. Under
     * {@link FailurePolicy#FALLBACK}, updates return a rejection while load logs and skips the
     * operation. {@code save} still writes the current state.
     *
     * <p>The config itself is not frozen. Every holder of one config shares its state, so a
     * value another holder changes is visible through {@link ConfigHolder#data()} here too.
     * Read-only only guarantees that load and update operations are refused through the holder
     * created by this builder.
     *
     * <pre>{@code
     * ConfigHolder<MyModConfig> config = LiteConfig.holder(MyModConfig.class)
     *     .modId("mymod")
     *     .readOnly()
     *     .create();
     * }</pre>
     *
     * @return this builder
     */
    public ConfigBuilder<T> readOnly() {
        this.readOnly = true;
        return this;
    }

    /**
     * Creates a holder using the configured access mode.
     *
     * <p>Synchronous methods wait for this config's serial worker lane, so failures still surface
     * on the calling stack. Asynchronous methods return the queued work.
     *
     * <p>Building touches disk the first time this config is built. It resolves file paths,
     * rejects invalid config models, registers extension validators, and validates defaults;
     * then it reads what is on disk and writes the accepted state back. Every file the root owns
     * exists once this returns, and the holder starts in sync with disk. A first run gets its
     * defaults written out; an existing valid file seeds the holder; an invalid one is moved
     * aside under a fallback read policy and replaced with defaults. Building the same config
     * again returns another handle onto the state that first build produced.
     *
     * @return a ready-to-use holder for {@code T}; never {@code null}
     * @throws LiteConfigException if the required
     *         mod id is missing, the model violates LiteConfig rules, two config types resolve to
     *         one file, defaults fail validation, or a strict read or write policy refuses the
     *         build-time load or write
     */
    public ConfigHolder<T> create() {
        return build();
    }

    private ConfigHolder<T> build() {
        RegisteredConfig<T> registration = ConfigRegistry.register(settings(), ConfigSyncRegistry::register);
        ConfigHolder<T> holder = new ConfigHolderImplementation<>(registration, readOnly);
        try {
            listenerRegistrations.forEach(register -> register.accept(holder));
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
