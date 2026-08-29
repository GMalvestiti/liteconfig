package com.gmalvestiti.minecraft.liteconfig.api.annotations;

import com.gmalvestiti.minecraft.liteconfig.api.ConfigFormat;
import com.gmalvestiti.minecraft.liteconfig.api.FailurePolicy;
import com.gmalvestiti.minecraft.liteconfig.api.spi.StateCloner;
import com.gmalvestiti.minecraft.liteconfig.engine.state.StateClonerImplementation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks one class as a config file root.
 *
 * <p>The class must expose a public no-argument constructor, whose field values are the
 * defaults. It may hold any field the JSON provider supports, but never a field typed with
 * another {@code @Config} class. Each registration owns exactly one file:
 *
 * <pre>{@code
 * @Config(name = "mymod")
 * public final class MyModConfig {
 *     public boolean showHints = true;
 *     public int hudScale = 2;
 * }
 * }</pre>
 *
 * <p>The file is {@code <baseDir>/<path>/<name><extension>}: {@link #path()} contributes only
 * directories, {@link #name()} contributes only the file, {@link #format()} contributes the
 * extension, and {@code baseDir} is the platform config directory unless
 * {@code ConfigBuilder.baseDir(...)} overrides it. The example above resolves to
 * {@code config/mymod.json5}.
 *
 * <p>Name the file after your mod. The owning mod id is <em>not</em> part of the path, so a
 * generic {@code name} such as {@code "config"} or {@code "client"} in the shared config root
 * collides with whichever other mod picked it first, and the second holder to be created fails
 * with {@code ConfigError.CONFLICTING_CONFIG_PATH}:
 *
 * <pre>{@code
 * @Config(name = "mymod")                     // config/mymod.json5        — one file
 * @Config(name = "client", path = "mymod")    // config/mymod/client.json5 — several files
 * @Config(name = "client")                    // config/client.json5       — avoid: not yours alone
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Config {

    /**
     * Names the config file, and nothing else.
     *
     * <p>Exactly one file-name component — never a directory, and the only place the file name
     * comes from. Name it after your mod, since the config root is shared with every other mod.
     * The extension of the declared {@link #format()} is appended when missing, ignoring case:
     *
     * <pre>{@code
     * name = "mymod"         -> mymod.json5        // recommended for a single file
     * name = "mymod.json5"   -> mymod.json5
     * name = "client"        -> client.json5       // only inside your own path, see path()
     * }</pre>
     *
     * <p>Blank values, the bare extension {@code .json5}, and values containing {@code /} or
     * {@code \} are rejected with {@code ConfigError.INVALID_CONFIG_NAME}; put directories in
     * {@link #path()} instead.
     *
     * @return the config file name, with or without the format's extension
     */
    String name();

    /**
     * Names the directories that contain the file, and nothing else.
     *
     * <p>A relative, {@code /}-separated directory path resolved under the config directory —
     * never a file name and never a file extension. Empty by default, which puts the file
     * straight in the config root; give it your mod id once you own more than one file. Missing
     * directories are created on the first save:
     *
     * <pre>{@code
     * path = "",       name = "mymod"   -> config/mymod.json5            // one file
     * path = "mymod",  name = "client"  -> config/mymod/client.json5    // several files
     * path = "mymod/gui", name = "hud" -> config/mymod/gui/hud.json5
     *
     * // Wrong: the file name belongs in name(), so this creates a directory called client.json5
     * path = "mymod/client.json5" -> config/mymod/client.json5/client.json5
     * }</pre>
     *
     * <p>Absolute paths, and relative ones that escape the config root once normalized, are
     * rejected with {@code ConfigError.INVALID_CONFIG_PATH}.
     *
     * @return a relative directory path, or {@code ""} for the mod config root
     */
    String path() default "";

    /**
     * Chooses the file format, and with it the file extension.
     *
     * <p>{@link ConfigFormat#JSON} by default, which writes JSON5 so comments stay in the file.
     * {@link ConfigFormat#TOML} writes TOML instead. The config class itself does not change:
     *
     * <pre>{@code
     * @Config(name = "mymod")                              // config/mymod.json5
     * @Config(name = "mymod", format = ConfigFormat.TOML)  // config/mymod.toml
     * }</pre>
     *
     * <p>Decide before shipping. Changing the format later changes the file name, so the old
     * file stays on disk untouched and the new one starts from defaults.
     *
     * @return the format this config is stored in; never {@code null}
     */
    ConfigFormat format() default ConfigFormat.JSON;

    /**
     * Chooses how live config state is copied.
     *
     * <p>The default performs a complete JSON tree round-trip. A custom implementation can avoid
     * that overhead, but it must expose a no-argument constructor and return fully independent
     * copies:
     *
     * <pre>{@code
     * @Config(name = "mymod", stateCloner = MyModConfigCloner.class)
     * public final class MyModConfig { }
     * }</pre>
     *
     * @return the cloner implementation shared by every holder of this config
     */
    Class<? extends StateCloner<?>> stateCloner() default StateClonerImplementation.class;

    /**
     * Chooses how load, parse, migration, and load-time validation failures behave.
     *
     * <p>{@link FailurePolicy#FALLBACK} backs up malformed files and restores defaults.
     * {@link FailurePolicy#STRICT} throws the failure instead.
     *
     * @return the read failure policy
     */
    FailurePolicy readFailurePolicy() default FailurePolicy.FALLBACK;

    /**
     * Chooses how file write failures behave.
     *
     * <p>{@link FailurePolicy#FALLBACK} logs the failure and keeps the current in-memory state.
     * {@link FailurePolicy#STRICT} throws the failure instead.
     *
     * @return the write failure policy
     */
    FailurePolicy writeFailurePolicy() default FailurePolicy.FALLBACK;

    /**
     * Chooses how validation failures from updates behave.
     *
     * <p>{@link FailurePolicy#FALLBACK} rejects the candidate and keeps the current state.
     * {@link FailurePolicy#STRICT} throws the failure instead.
     *
     * @return the update failure policy
     */
    FailurePolicy updateFailurePolicy() default FailurePolicy.FALLBACK;

    /**
     * Heads the config file with an explanation of what it is for.
     *
     * <p>The class-level counterpart of {@link Entry#comment()}: that one documents a
     * single field, this one documents the file as a whole. Use it for what the file is, how to
     * get the defaults back, or where the real documentation lives — the things a player wants
     * to read before the first setting:
     *
     * <pre>{@code
     * @Config(name = "mymod", comment = "MyMod settings. Delete this file to restore the defaults.")
     * public final class MyModConfig { }
     *
     * @Config(name = "mymod", comment = {
     *     "MyMod settings.",
     *     "",
     *     "Delete this file to restore the defaults."
     * })
     * public final class MyModConfig { }
     * }</pre>
     *
     * <p>One entry per line of the file; a blank entry is a blank comment line. An entry
     * containing newlines is split, so a text block works as well as an array. Write the text,
     * not the comment markers — each format renders them its own way.
     *
     * <p>Purely cosmetic: a comment never affects parsing, validation, or defaults.
     *
     * @return the header lines, or an empty array for no header
     */
    String[] comment() default {};

    /**
     * Sends this config's values from the server to connected clients.
     *
     * <p>Off by default: a config stays where it is written unless it is asked to travel. Turn it
     * on when its values describe rules clients have to render or enforce:
     *
     * <pre>{@code
     * @Config(name = "rules", sync = true)
     * public final class RulesConfig {
     *     public int maxTeamSize = 4;
     *     public boolean friendlyFire = false;
     * }
     * }</pre>
     *
     * <p>For a config that shares only a value or two, leave this alone and mark those fields
     * with {@link Entry#sync()} instead.
     *
     * <p>Values travel one way, from the server to its clients: a client's own config never
     * reaches a server, which would want a command or a screen for that anyway. The file itself
     * lives where every other config file lives, under the name and path declared here.
     *
     * @return {@code true} to send every value of this config to connected clients
     */
    boolean sync() default false;

    /**
     * Declares which revision of this config the class expects, enabling migrations.
     *
     * <p>Zero, the default, means the config is unversioned: nothing is written to the file and
     * no migration ever runs. Set it to {@code 2} or higher the first time a change to the class
     * would otherwise strand existing files, and declare the step that bridges the gap:
     *
     * <pre>{@code
     * @Config(name = "mymod", version = 2)
     * public final class MyModConfig {
     *     public Hud hud = new Hud();   // hudScale used to live at the root
     *
     *     @Migration(from = 1)
     *     static void toVersion2(ConfigData data) {
     *         data.rename("hudScale", "hud.scale");
     *     }
     * }
     * }</pre>
     *
     * <p>Once enabled, the version is stored in the file under the reserved key
     * {@code configVersion}, which no field of the class may claim. A file without that key is
     * read as version {@code 1}, so files written before you started versioning migrate
     * correctly. When present, the key must be a positive whole number in the {@code int} range;
     * {@code null}, text, booleans, containers, fractions, zero, negatives, and out-of-range
     * numbers are malformed config data.
     *
     * <p>Loading walks every step from the file's version up to this one, so each boundary needs
     * a {@link Migration @Migration} method — a gap is reported as
     * {@code ConfigError.MISSING_CONFIG_MIGRATION}. A file claiming a <em>higher</em> version
     * than this was written by a newer build of the mod and cannot be understood, so it is
     * refused as {@code ConfigError.CONFIG_VERSION_TOO_NEW} and handled by the read failure
     * policy — under {@code FALLBACK} it is backed up and replaced with defaults.
     *
     * @return the current config revision, or {@code 0} to leave versioning off
     */
    int version() default 0;
}
