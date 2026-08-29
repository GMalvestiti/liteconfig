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

    static DeclaredConfig.Property propertyOf(Class<?> owner, String key) {
        return owner == null ? null : DeclaredConfig.of(owner).property(key);
    }

    static String commentOfClass(Class<?> type) {
        return type == null ? null : join(DeclaredConfig.of(type).comment());
    }

    static String commentOf(DeclaredConfig.Property property) {
        if (property == null) {
            return null;
        }

        return property.comment().isEmpty() ? commentOfClass(property.type()) : join(property.comment());
    }

    private static String join(List<String> lines) {
        return lines.isEmpty() ? null : String.join("\n", lines);
    }
}
