package com.gmalvestiti.minecraft.liteconfig.storage.format;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.toml.TomlParser;
import com.electronwill.nightconfig.toml.TomlWriter;
import com.gmalvestiti.minecraft.liteconfig.metadata.DeclaredConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

final class TomlTextFormat implements ConfigTextFormat {

    static final ConfigTextFormat INSTANCE = new TomlTextFormat();

    private TomlTextFormat() {}

    @Override
    public JsonObject readTree(String text) {
        CommentedConfig parsed = new TomlParser().parse(new StringReader(text));
        JsonElement tree = toJsonTree(parsed);
        return tree.isJsonObject() ? tree.getAsJsonObject() : null;
    }

    @Override
    public String writeTree(JsonObject tree, Class<?> type) {
        CommentedConfig config = CommentedConfig.inMemory();
        fill(config, tree, type);
        return header(TextFormatHelper.commentOfClass(type)) + new TomlWriter().writeToString(config);
    }

    private void fill(CommentedConfig config, JsonObject object, Class<?> owner) {
        for (Map.Entry<String, JsonElement> property : object.entrySet()) {
            JsonElement value = property.getValue();
            if (value.isJsonNull()) {
                continue;
            }

            String key = property.getKey();
            List<String> path = List.of(key);
            DeclaredConfig.Property declared = TextFormatHelper.propertyOf(owner, key);

            if (value.isJsonObject()) {
                CommentedConfig table = config.createSubConfig();

                Class<?> nested = declared == null ? null : declared.type();
                fill(table, value.getAsJsonObject(), nested);

                config.set(path, table);
            } else {
                config.set(path, toTomlValue(value));
            }

            String comment = TextFormatHelper.commentOf(declared);
            if (comment != null) {
                config.setComment(path, comment);
            }
        }
    }

    private Object toTomlValue(JsonElement element) {
        if (element.isJsonObject()) {
            return toTomlTable(element.getAsJsonObject());
        }

        if (element.isJsonArray()) {
            List<Object> values = new ArrayList<>();

            for (JsonElement item : element.getAsJsonArray()) {
                if (!item.isJsonNull()) {
                    values.add(toTomlValue(item));
                }
            }

            return values;
        }

        JsonPrimitive primitive = element.getAsJsonPrimitive();

        if (primitive.isBoolean()) {
            return primitive.getAsBoolean();
        }

        if (primitive.isNumber()) {
            return toTomlNumber(primitive);
        }

        return primitive.getAsString();
    }

    /**
     * Keeps whole numbers whole. Written as one ternary the two branches would be promoted to a
     * common type and every integer would land in the file as a float.
     */
    private Object toTomlNumber(JsonPrimitive primitive) {
        double value = primitive.getAsDouble();
        // NaN and the infinities carry no '.', but they are not integers either.
        if (hasFraction(primitive.getAsString()) || !Double.isFinite(value)) {
            return value;
        }
        return primitive.getAsLong();
    }

    private boolean hasFraction(String literal) {
        return literal.indexOf('.') >= 0 || literal.indexOf('e') >= 0 || literal.indexOf('E') >= 0;
    }

    private CommentedConfig toTomlTable(JsonObject object) {
        CommentedConfig table = CommentedConfig.inMemory();
        // Nothing here has a declaring field, so nothing inside it can be commented.
        fill(table, object, null);
        return table;
    }

    private JsonElement toJsonTree(UnmodifiableConfig config) {
        JsonObject object = new JsonObject();

        for (UnmodifiableConfig.Entry property : config.entrySet()) {
            object.add(property.getKey(), toJsonElement(property.getValue()));
        }

        return object;
    }

    private JsonElement toJsonElement(Object value) {
        return switch (value) {
            case null -> JsonNull.INSTANCE;
            case UnmodifiableConfig table -> toJsonTree(table);
            case Iterable<?> items -> toJsonArray(items);
            case Boolean flag -> new JsonPrimitive(flag);
            case Number number -> new JsonPrimitive(number);
            default -> new JsonPrimitive(String.valueOf(value));
        };
    }

    private JsonArray toJsonArray(Iterable<?> items) {
        JsonArray array = new JsonArray();
        items.forEach(item -> array.add(toJsonElement(item)));
        return array;
    }

    private String header(String comment) {
        if (comment == null) {
            return "";
        }

        return comment.lines().map("#"::concat).collect(Collectors.joining("\n", "", "\n\n"));
    }
}
