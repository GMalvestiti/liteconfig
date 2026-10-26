package com.gmalvestiti.minecraft.liteconfig.storage.format;

import com.gmalvestiti.minecraft.liteconfig.api.annotations.Entry;
import com.gmalvestiti.minecraft.liteconfig.metadata.DeclaredConfig;

import java.util.List;

/**
 * Answers what the codecs need to know about a config class.
 *
 * <p>Lookups are keyed by the name in the file rather than the Java field name, so
 * {@link Entry#name()} renames resolve here.
 */
final class TextFormatHelper {

    private TextFormatHelper() {}

    static DeclaredConfig.Property property(Class<?> owner, String key) {
        return owner == null ? null : DeclaredConfig.of(owner).property(key);
    }

    static int version(Class<?> type) {
        return type == null ? 0 : DeclaredConfig.of(type).version();
    }

    static String comment(Class<?> type) {
        return type == null ? null : block(DeclaredConfig.of(type).comment());
    }

    static String comment(DeclaredConfig.Property property) {
        if (property == null) {
            return null;
        }

        return property.comment().isEmpty() ? comment(property.type()) : block(property.comment());
    }

    private static String block(List<String> lines) {
        return lines.isEmpty() ? null : String.join("\n", lines);
    }
}
