package com.gmalvestiti.minecraft.liteconfig.storage.format;

import com.gmalvestiti.minecraft.liteconfig.api.ConfigFormat;
import com.google.gson.JsonObject;

/**
 * Turns config trees into file text and back, for one {@link ConfigFormat}.
 *
 * <p>An implementation owns the text format and nothing else: storage handles files, missing files, atomic
 * replacement, and failure attribution, {@code ConfigBinder} handles the object binding,
 * and migrations run on the tree in between. What is left here is the tree-to-text
 * step, plus writing the comments declared with {@code @Config} and {@code @Entry}.
 *
 * <pre>{@code
 * ConfigTextFormat format = ConfigTextFormat.of(ConfigFormat.TOML);
 * String text = format.writeTree(ConfigBinder.toTree(config).getAsJsonObject(), MyModConfig.class);
 * JsonObject parsed = format.readTree(text);
 * }</pre>
 *
 * <p>Implementations may throw any {@link RuntimeException}; storage catches those and reports
 * {@code ConfigError.MALFORMED_CONFIG_DATA} for reads and {@code ConfigError.IO_SAVE_FAILURE}
 * for writes. They must be stateless since one instance serves every holder.
 */
public interface ConfigTextFormat {

    /**
     * Returns the implementation that reads and writes {@code format}.
     */
    static ConfigTextFormat of(ConfigFormat format) {
        return switch (format) {
            case JSON -> Json5TextFormat.INSTANCE;
            case TOML -> TomlTextFormat.INSTANCE;
        };
    }

    /**
     * Parses file text into a format-neutral tree.
     *
     * @param text raw file content; never {@code null}
     * @return the parsed tree, or {@code null} when the text holds no object, which storage
     *         reports as malformed data
     * @throws RuntimeException when the text cannot be parsed
     */
    JsonObject readTree(String text);

    /**
     * Renders a tree as the text to write to disk, comments included.
     *
     * @param tree the values to write; never {@code null}
     * @param type the config class the tree came from, which supplies the comments; never {@code null}
     * @return the complete file content; never {@code null}
     * @throws RuntimeException when the tree cannot be rendered
     */
    String writeTree(JsonObject tree, Class<?> type);
}
