package com.gmalvestiti.minecraft.liteconfig.api.migration;

import com.gmalvestiti.minecraft.liteconfig.api.annotations.Entry;

import java.util.Optional;

/**
 * The contents of a config file as it was found on disk, before it becomes a config object.
 *
 * <p>A migration cannot work with the config class: the whole point of migrating is that the
 * file still uses keys the class has since renamed, moved, or dropped. So it works with this
 * view instead — the parsed file, addressed by dotted paths, in whatever shape it happens to
 * have. The mutating calls chain, so a step usually reads as a short list of edits:
 *
 * <pre>{@code
 * data.rename("hudscale", "hud.scale")
 *     .set("hud.scale", Math.min(8, data.integer("hud.scale", 2)))
 *     .remove("legacyRenderer");
 * }</pre>
 *
 * <p>Paths address nested objects with dots, so {@code "hud.scale"} is the {@code scale} key of
 * the {@code hud} object. Keys are the ones in the file, which are the Java field names unless
 * {@link Entry#name()} renamed them.
 *
 * <p>Reads come in two shapes. The {@code Optional} ones tell you whether the file had a value
 * at all; the ones taking a fallback answer with a plain value, which is what a migration
 * usually wants since a file being migrated may well be missing the key:
 *
 * <pre>{@code
 * int scale = data.integer("hud.scale", 2);                  // 2 when absent or not a number
 * data.number("hud.scale").ifPresent(this::recordOldScale);  // only when the file had one
 * }</pre>
 *
 * <p>Anything the config class no longer declares is dropped after the migrations run, so a
 * migration only has to produce the keys the current class expects. Values it leaves behind do
 * no harm.
 */
public interface ConfigData {

    /**
     * Reports whether the file holds a value at {@code path}.
     *
     * <p>A path holding an explicit {@code null} counts as present.
     *
     * @param path the dotted path to test; may be {@code null}, which is never present
     * @return {@code true} when the path exists in the file
     */
    boolean has(String path);

    /**
     * Reads {@code path} as text.
     *
     * @param path the dotted path to read; may be {@code null}
     * @return the value, or empty when the path is absent, {@code null}, or not a plain value
     */
    Optional<String> string(String path);

    /**
     * Reads {@code path} as a number.
     *
     * <p>Call {@link Number#intValue()} or {@link Number#doubleValue()} on the result; the file
     * format does not always distinguish the two. {@link #integer(String, int)} and
     * {@link #decimal(String, double)} do that for you.
     *
     * @param path the dotted path to read; may be {@code null}
     * @return the value, or empty when the path is absent or does not hold a number
     */
    Optional<Number> number(String path);

    /**
     * Reads {@code path} as a boolean.
     *
     * @param path the dotted path to read; may be {@code null}
     * @return the value, or empty when the path is absent or does not hold a boolean
     */
    Optional<Boolean> bool(String path);

    /**
     * Reads {@code path} as text, falling back when the file has nothing usable there.
     *
     * @param path the dotted path to read; may be {@code null}
     * @param fallback the value to use when the path is absent or does not hold text
     * @return the stored text, or {@code fallback}
     */
    default String string(String path, String fallback) {
        return string(path).orElse(fallback);
    }

    /**
     * Reads {@code path} as a whole number, falling back when the file has nothing usable there.
     *
     * @param path the dotted path to read; may be {@code null}
     * @param fallback the value to use when the path is absent or does not hold a number
     * @return the stored number truncated to an {@code int}, or {@code fallback}
     */
    default int integer(String path, int fallback) {
        return number(path).map(Number::intValue).orElse(fallback);
    }

    /**
     * Reads {@code path} as a decimal number, falling back when the file has nothing usable
     * there.
     *
     * @param path the dotted path to read; may be {@code null}
     * @param fallback the value to use when the path is absent or does not hold a number
     * @return the stored number as a {@code double}, or {@code fallback}
     */
    default double decimal(String path, double fallback) {
        return number(path).map(Number::doubleValue).orElse(fallback);
    }

    /**
     * Reads {@code path} as a boolean, falling back when the file has nothing usable there.
     *
     * @param path the dotted path to read; may be {@code null}
     * @param fallback the value to use when the path is absent or does not hold a boolean
     * @return the stored flag, or {@code fallback}
     */
    default boolean bool(String path, boolean fallback) {
        return bool(path).orElse(fallback);
    }

    /**
     * Writes a value at {@code path}, creating the objects along the way and replacing whatever
     * was there.
     *
     * <pre>{@code
     * data.set("hud.scale", 3)            // creates the hud object if it is missing
     *     .set("profileName", "default");
     * }</pre>
     *
     * @param path the dotted path to write; must not be {@code null} or blank
     * @param value a {@link String}, {@link Number}, {@link Boolean}, {@link Character}, or
     *     {@code null}
     * @return this data, so edits can be chained
     * @throws IllegalArgumentException if {@code path} is blank, or {@code value} is of an
     *         unsupported type
     * @throws NullPointerException if {@code path} is {@code null}
     */
    ConfigData set(String path, Object value);

    /**
     * Deletes whatever is at {@code path}, including a whole nested object.
     *
     * <p>Does nothing when the path is absent, so a step can drop a key it is not sure about.
     *
     * @param path the dotted path to delete; may be {@code null}
     * @return this data, so edits can be chained
     */
    ConfigData remove(String path);

    /**
     * Moves a value from one path to another, which is how a field is renamed or nested.
     *
     * <p>Does nothing when {@code from} is absent, so a migration can safely run against a file
     * that never had the old key. In that case a blank {@code to} is not inspected:
     *
     * <pre>{@code
     * data.rename("hudscale", "hud.scale");
     * }</pre>
     *
     * @param from the dotted path to move from; may be {@code null}
     * @param to the dotted path to move to; must not be {@code null} or blank
     * @return this data, so edits can be chained
     * @throws IllegalArgumentException if {@code from} exists and {@code to} is blank
     * @throws NullPointerException if {@code to} is {@code null}
     */
    ConfigData rename(String from, String to);
}
