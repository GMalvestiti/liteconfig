package com.gmalvestiti.minecraft.liteconfig.api.annotations;

import com.gmalvestiti.minecraft.liteconfig.api.ConfigHolder;
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
     * Refuses runtime changes to a field that only takes effect while the game is starting.
     *
     * <p>Some settings are read once at startup. Writing them at runtime leaves the file disagreeing with the running game, so the attempt fails immediately instead:
     *
     * <pre>{@code
     * UpdateResult result = holder.updateAndSave(config -> config.useNativeRenderer = true);
     * if (!result.accepted()) {
     *     result.violations().forEach(v -> LOGGER.warn("{}: {}", v.id(), v.message()));
     * }
     * }</pre>
     *
     * <p>The whole update is rejected, not just the field, so a mutator that touches several
     * fields never applies half of them. Rejection follows the update failure policy: it throws
     * under {@code STRICT} and returns a rejected {@link UpdateResult}
     * <p>Loading is not an update: {@link ConfigHolder#load()} replaces the state wholesale, so
     * a player who edits the file and restarts still gets the new value.
     *
     * @return {@code true} to reject every update that changes this field
     */
    boolean restart() default false;

    /**
     * Sends this field from the server to connected clients.
     *
     * <p>Off by default. A whole config travels when {@link Config#sync()} says so; this sends a
     * single field out of a config that otherwise keeps to itself:
     *
     * <pre>{@code
     * @Config(name = "rules")
     * public final class RulesConfig {
     *
     *     @Entry(sync = true)
     *     public int maxTeamSize = 4;                 // clients need this
     *
     *     public String auditLogPath = "logs/audit";  // clients never see it
     * }
     * }</pre>
     *
     * <p>Sending covers everything below the field, so marking a nested object sends every value
     * it holds. A field of a config that already syncs is sent either way — this adds fields, it
     * never takes them back out.
     *
     * <p>A field that is not sent has no value on a connected client at all: reading it there
     * yields the declared default, never the server's value. That is the point — it is how a
     * server keeps something to itself.
     *
     * <p>Values travel one way, from the server to its clients. A client's own config never
     * reaches a server.
     *
     * @return {@code true} to send this field to connected clients
     */
    boolean sync() default false;

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
     * @Entry(callback = "applyHudScale")
     * public int hudScale = 2;
     *
     * private void applyHudScale(Integer oldValue, Integer newValue, boolean fromSync) {
     *     hudRenderer.setScale(newValue);
     * }
     * }</pre>
     *
     * <p>The callback runs only after a changed value has been validated and published. It runs for
     * successful load, update, and server transitions; {@code fromSync} is {@code true} for
     * server-supplied or server-reverted values. Local callbacks run on the thread performing the
     * holder operation; synced callbacks run on the Minecraft client thread. Rejected updates and
     * structurally unchanged values run nothing. A callback must not mutate the config object.
     * Failures are logged like lifecycle listener failures and do not undo the state transition or
     * prevent later callbacks.
     *
     * @return the callback method name, or {@code ""} to disable the callback
     */
    String callback() default "";
}
