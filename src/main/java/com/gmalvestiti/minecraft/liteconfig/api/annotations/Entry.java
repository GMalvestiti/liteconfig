package com.gmalvestiti.minecraft.liteconfig.api.annotations;

import com.gmalvestiti.minecraft.liteconfig.api.ConfigHolder;
import com.gmalvestiti.minecraft.liteconfig.api.SyncMode;
import com.gmalvestiti.minecraft.liteconfig.api.UpdateResult;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Describes one config field: how it is named in the file, what it does, and when it may change.
 *
 * <p>Everything is optional, so annotate only the fields that need it:
 *
 * <pre>{@code
 * @Config(name = "mymod")
 * public final class MyModConfig {
 *
 *     @Entry(comment = "Scale of the on-screen HUD.")
 *     public int hudScale = 2;
 *
 *     @Entry(name = "hide-hints")
 *     public boolean hideHints = false;
 *
 *     @Entry(comment = "Takes effect after a restart.", restart = true)
 *     public boolean useNativeRenderer = false;
 * }
 * }</pre>
 *
 * <p>writes
 *
 * <pre>{@code
 * {
 *   // Scale of the on-screen HUD.
 *   "hudScale": 2,
 *   "hide-hints": false,
 *   // Takes effect after a restart.
 *   "useNativeRenderer": false
 * }
 * }</pre>
 *
 * <p>To exclude a field from the file entirely, use {@link Ignore} instead of this
 * annotation. The same annotation drives every format, so a config class annotated once reads
 * and writes identically as JSON5 and as TOML. To comment the class itself rather than a field,
 * use {@link Config#comment()}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Entry {

    /**
     * Names the field in the config file, decoupling it from the Java field name.
     *
     * <p>Empty by default, which uses the Java field name as-is. Set it when the file should
     * read differently from the code — a kebab-case key, a name kept stable across a Java-side
     * rename, or a key that is not a legal Java identifier:
     *
     * <pre>{@code
     * public boolean hideHints = false; // results in "hideHints"
     *
     * @Entry(name = "hide-hints")
     * public boolean hideHints = false; // results in "hide-hints"
     * }</pre>
     *
     * <p>The name applies to reading and writing alike, so an existing file keyed by the Java
     * name is not migrated: it is read as a missing value and the field keeps its default until
     * the next save writes the new key.
     *
     * <p>Two fields of one class must not resolve to the same name, whether through this
     * attribute, inheritance, or by colliding with another field's Java name. Names cannot contain
     * {@code "."}, because dots separate nested properties in metadata and synchronization paths.
     * Invalid or duplicate effective names are rejected when the config model is built.
     *
     * @return the key to use in the file, or {@code ""} to use the Java field name
     */
    String name() default "";

    /**
     * Explains the field in the config file.
     *
     * <pre>{@code
     * @Entry(comment = "Scale of the on-screen HUD.")
     * public int hudScale = 2;
     *
     * @Entry(comment = {"Scale of the on-screen HUD.", "Between 1 and 4."})
     * public int hudScale = 2;
     * }</pre>
     *
     * <p>One array entry becomes one line in the file. A blank entry leaves a blank line, which is
     * how you separate paragraphs. An entry containing newlines is split too, so a text block
     * works as well as an array.
     *
     * <p>Write the text, not the markers. Each format renders them its own way, and a marker that
     * leaks into your text is defused rather than written as-is.
     *
     * <p>Fields inside collections and maps are not commented, because their entries have no
     * single declaring field.
     *
     * <p>Purely cosmetic: comments never affect parsing, validation, or defaults.
     *
     * @return the comment lines, or an empty array for no comment
     */
    String[] comment() default {};

    /**
     * Names the translation key a config screen should use for this field.
     *
     * <p>Lite Config never resolves the key itself — it stores it and publishes it on
     * {@link com.gmalvestiti.minecraft.liteconfig.api.metadata.ConfigMetadata}, so a screen built on
     * top of the library can label the field without inventing its own naming convention:
     *
     * <pre>{@code
     * @Entry(translationKey = "mymod.config.hudScale")
     * public int hudScale = 2;
     * }</pre>
     *
     * <p>Purely descriptive: the key never affects the file, parsing, validation, or defaults.
     *
     * @return the translation key, or {@code ""} when the field has none
     */
    String translationKey() default "";

    /**
     * Defers changes to a field until its ownership scope restarts.
     *
     * <p>CLIENT and SERVER edits are accepted as pending until game startup. WORLD edits wait
     * until the world is loaded again; initial remote sync applies that world's settings on
     * joining. Other fields can change immediately. Save local selections to retain them across
     * loads:
     *
     * <pre>{@code
     * UpdateResult result = holder.updateAndSave(config -> config.useNativeRenderer = true);
     * if (!result.accepted()) {
     *     result.violations().forEach(v -> LOGGER.warn("{}: {}", v.id(), v.message()));
     * }
     * }</pre>
     *
     * <p>Synced SERVER changes are persisted for the client's next game startup and exposed
     * through {@link ConfigHolder#pending()} without changing active values.
     *
     * <p>Explicit {@link ConfigHolder#load()} also leaves restart-only changes pending for every
     * config type. For join handling of synced SERVER values, see {@link Config#restartSyncPolicy()}.
     *
     * @return whether changes require a game restart or world reload according to ownership
     */
    boolean restart() default false;

    /**
     * Chooses whether this field is sent from the server to connected clients.
     *
     * <p>Defaults to {@link SyncMode#INHERIT}: follow the parent section or {@link Config#sync()}.
     * Adding an entry annotation only for a comment or listener leaves that choice unchanged.
     * Use {@link SyncMode#ENABLED} to send a field from a config that otherwise stays local:
     *
     * <pre>{@code
     * @Config(name = "rules")
     * public final class RulesConfig {
     *
     *     @Entry(sync = SyncMode.ENABLED)
     *     public int maxTeamSize = 4;                 // clients need this
     *
     *     public String auditLogPath = "logs/audit";  // clients never see it
     * }
     * }</pre>
     *
     * <p>Use {@link SyncMode#DISABLED} to keep a field local under config-wide sync:
     *
     * <pre>{@code
     * @Config(name = "rules", sync = SyncMode.ENABLED)
     * public final class Rules {
     *     public int maxTeamSize = 4;
     *
     *     @Entry(sync = SyncMode.DISABLED)
     *     public String auditLogPath = "logs/audit";
     * }
     * }</pre>
     *
     * <p>Enabling a nested section includes its persisted leaves, except disabled descendants.
     * Disabling a section excludes its entire subtree, even descendants marked
     * {@code SyncMode.ENABLED}. Excluded values never enter the network schema or payload;
     * clients retain their local values or defaults. Persistence, copying, and validation
     * remain unchanged.
     *
     * <p>Values travel only from server to client. CLIENT-owned configs never synchronize,
     * regardless of this mode.
     *
     * @return the sync selection mode, {@link SyncMode#INHERIT} by default
     */
    SyncMode sync() default SyncMode.INHERIT;

    /**
     * Names the instance method called after this field changes in an accepted state transition.
     *
     * <p>The method belongs to the class that declares the field. It may be private or inherited,
     * but must have the exact shape {@code void method(BoxedType oldValue, BoxedType newValue,
     * boolean fromSync)}. Primitive fields use their wrapper type, so an {@code int} field receives
     * {@link Integer}; generic fields use their erased declared type. LiteConfig validates the
     * declaration while the holder is built:
     *
     * <pre>{@code
     * @Entry(listener = "applyHudScale")
     * public int hudScale = 2;
     *
     * private void applyHudScale(Integer oldValue, Integer newValue, boolean fromSync) {
     *     hudRenderer.setScale(newValue);
     * }
     * }</pre>
     *
     * <p>The listener runs only after a changed value has been validated and published. It runs for
     * successful explicit loads, updates, and server sync transitions, but not for the build-time
     * load; {@code fromSync} is {@code true} only for server-supplied values.
     *
     * <p>Listeners run synchronously on the initiating caller after the state is published.
     * Rejected updates and structurally unchanged values run nothing.
     *
     * A listener must not mutate the config object. Failures are logged like lifecycle event
     * failures and do not undo the state transition or prevent later listeners.
     *
     * <p>A listener may start another holder operation, including through a different holder.
     *
     * @return the listener method name, or {@code ""} to disable the listener
     */
    String listener() default "";
}
