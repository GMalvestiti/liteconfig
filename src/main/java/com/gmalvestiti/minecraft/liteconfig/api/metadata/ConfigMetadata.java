package com.gmalvestiti.minecraft.liteconfig.api.metadata;

import com.gmalvestiti.minecraft.liteconfig.api.ConfigHolder;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Config;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Entry;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * A structural description of one config root, obtained from {@link ConfigHolder#metadata()}.
 *
 * <p>Lite Config does not draw config screens, but it knows everything a screen would need to
 * draw one. The metadata is that knowledge, published as plain data: the properties of the root,
 * their defaults, their documentation, and the rules they enforce. Nothing here reads or writes
 * the current state — use {@link ConfigHolder#data()} for values.
 *
 * <pre>{@code
 * ConfigMetadata metadata = holder.metadata();
 *
 * for (ConfigProperty property : metadata.properties()) {
 *     LOGGER.info("{} ({}) defaults to {}", property.path(), property.type(), property.defaultValue());
 * }
 *
 * ConfigProperty scale = metadata.property("hud.scale").orElseThrow();
 * scale.constraints().max().ifPresent(max -> LOGGER.info("hud scale tops out at {}", max));
 * }</pre>
 *
 * <p>A {@link Config @Config} root describes one file, and {@link #version()} is that file's
 * declared version.
 *
 * <p>The metadata structure is immutable and computed once per config registration, so it is safe
 * to cache and share. Collection and map defaults are recursively wrapped in immutable snapshots;
 * array access returns a fresh copy. Mutable custom-codec defaults remain shared references and
 * must be treated as read-only.
 *
 * @param type the root config class this metadata describes
 * @param comment the lines declared with {@link Config#comment()}, empty when undocumented
 * @param version the declared {@link Config#version()}, or {@code 0} when versioning is off
 * @param properties the top-level properties, in declaration order
 */
public record ConfigMetadata(
    Class<?> type,
    List<String> comment,
    int version,
    List<ConfigProperty> properties
) {

    public ConfigMetadata {
        comment = List.copyOf(comment);
        properties = List.copyOf(properties);
    }

    /**
     * Looks up a property by its dotted path, at any depth.
     *
     * <pre>{@code
     * metadata.property("hud.scale");   // nested
     * metadata.property("showHints");   // top level
     * }</pre>
     *
     * @param path the dotted path to find; may be {@code null}, which finds nothing
     * @return the matching property, or empty when the path is not part of this metadata
     */
    public Optional<ConfigProperty> property(String path) {
        return MetadataTraversal.find(properties, path);
    }

    /**
     * Returns every property in the metadata, depth first, parents before their children.
     *
     * @return a stream over the whole tree; never {@code null}
     */
    public Stream<ConfigProperty> flatten() {
        return MetadataTraversal.stream(properties);
    }

    /**
     * Returns the values this config sends to connected clients, in a stable order.
     *
     * <p>Nothing is sent unless it was asked for, so this is empty for a config that keeps to
     * itself. What remains are the plain values — never the nested objects that hold them — that
     * {@link Config#sync()} or {@link Entry#sync()} put on the wire, ordered depth first by
     * declaration. That order does not depend on the state, so it is safe to rely on when
     * writing and reading a payload:
     *
     * <pre>{@code
     * for (ConfigProperty property : metadata.synced()) {
     *     encode(buffer, property.type(), property.path());
     * }
     * }</pre>
     *
     * <p>Values travel from the server to its clients and never the other way.
     *
     * @return the synced value properties in declaration order; never {@code null}
     */
    public List<ConfigProperty> synced() {
        return MetadataTraversal.synced(properties);
    }
}
