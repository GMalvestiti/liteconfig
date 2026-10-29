package com.gmalvestiti.minecraft.liteconfig.storage.format;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.toml.TomlParser;
import com.electronwill.nightconfig.toml.TomlWriter;
import com.gmalvestiti.minecraft.liteconfig.metadata.DeclaredConfig;
import com.gmalvestiti.minecraft.liteconfig.migration.ConfigMigrations;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.io.StringReader;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

final class TomlTextFormat implements ConfigTextFormat {

    static final ConfigTextFormat INSTANCE = new TomlTextFormat();

    private static final Pattern INTEGER_LITERAL = Pattern.compile("-?\\d+");

    private TomlTextFormat() {}

    @Override
    public JsonObject readTree(String text) {
        CommentedConfig parsed = new TomlParser().parse(new StringReader(text));
        return toJsonTree(parsed);
    }

    @Override
    public String writeTree(JsonObject tree, Class<?> type) {
        CommentedConfig config = CommentedConfig.inMemory();
        int version = TextFormatHelper.version(type);

        if (version > 0) {
            config.set(List.of(ConfigMigrations.VERSION_KEY), version);
        }

        fill(config, tree, type, version > 0);
        return header(TextFormatHelper.comment(type)) + new TomlWriter().writeToString(config);
    }

    private void fill(CommentedConfig config, JsonObject object, Class<?> owner, boolean skipVersion) {
        for (Map.Entry<String, JsonElement> property : object.entrySet()) {
            JsonElement value = property.getValue();
            String key = property.getKey();

            if (skipVersion && ConfigMigrations.VERSION_KEY.equals(key)) {
                continue;
            }

            if (value.isJsonNull()) {
                throw new IllegalArgumentException("TOML cannot store a null value for '" + key + "'");
            }

            List<String> path = List.of(key);
            DeclaredConfig.Property declared = TextFormatHelper.property(owner, key);

            if (value.isJsonObject()) {
                CommentedConfig table = config.createSubConfig();

                Class<?> nested = declared == null ? null : declared.type();
                fill(table, value.getAsJsonObject(), nested, false);

                config.set(path, table);
            } else {
                config.set(path, toTomlValue(value));
            }

            String comment = TextFormatHelper.comment(declared);
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
                if (item.isJsonNull()) {
                    throw new IllegalArgumentException("TOML cannot store a null array element");
                }

                values.add(toTomlValue(item));
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
     * Integer literals become {@code long} and are rejected when they overflow it, so they are never
     * silently truncated. Other numbers are written as floating point.
     */
    private Object toTomlNumber(JsonPrimitive primitive) {
        String literal = primitive.getAsString();
        if (!INTEGER_LITERAL.matcher(literal).matches()) {
            return primitive.getAsDouble();
        }

        try {
            return new BigInteger(literal).longValueExact();
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("TOML cannot store integer outside the long range: " + literal, overflow);
        }
    }

    private CommentedConfig toTomlTable(JsonObject object) {
        CommentedConfig table = CommentedConfig.inMemory();
        // Nothing here has a declaring field, so nothing inside it can be commented.
        fill(table, object, null, false);
        return table;
    }

    private JsonObject toJsonTree(UnmodifiableConfig config) {
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
