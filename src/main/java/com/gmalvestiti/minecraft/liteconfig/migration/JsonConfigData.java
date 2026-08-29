package com.gmalvestiti.minecraft.liteconfig.migration;

import com.gmalvestiti.minecraft.liteconfig.api.migration.ConfigData;
import com.gmalvestiti.minecraft.liteconfig.shared.PropertyPath;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.Objects;
import java.util.Optional;

/**
 * A {@link ConfigData} view over the parsed file tree.
 */
final class JsonConfigData implements ConfigData {

    private final JsonObject root;

    JsonConfigData(JsonObject root) {
        this.root = root;
    }

    @Override
    public boolean has(String path) {
        return find(path) != null;
    }

    @Override
    public Optional<String> string(String path) {
        return primitive(path)
            .filter(JsonPrimitive::isString)
            .map(JsonPrimitive::getAsString);
    }

    @Override
    public Optional<Number> number(String path) {
        return primitive(path)
            .filter(JsonPrimitive::isNumber)
            .map(JsonPrimitive::getAsNumber);
    }

    @Override
    public Optional<Boolean> bool(String path) {
        return primitive(path)
            .filter(JsonPrimitive::isBoolean)
            .map(JsonPrimitive::getAsBoolean);
    }

    @Override
    public ConfigData set(String path, Object value) {
        String[] steps = PropertyPath.split(path);
        parentOf(steps, true).add(PropertyPath.last(steps), toElement(value));
        return this;
    }

    @Override
    public ConfigData remove(String path) {
        if (path != null && !path.isBlank()) {

            String[] steps = PropertyPath.split(path);
            JsonObject parent = parentOf(steps, false);

            if (parent != null) {
                parent.remove(PropertyPath.last(steps));
            }
        }

        return this;
    }

    @Override
    public ConfigData rename(String from, String to) {
        Objects.requireNonNull(to, "to");

        JsonElement value = find(from);
        if (value == null) {
            return this;
        }

        String[] steps = PropertyPath.split(to);

        remove(from);
        parentOf(steps, true).add(PropertyPath.last(steps), value);

        return this;
    }

    private Optional<JsonPrimitive> primitive(String path) {
        JsonElement found = find(path);
        return found != null && found.isJsonPrimitive()
            ? Optional.of(found.getAsJsonPrimitive())
            : Optional.empty();
    }

    private JsonElement find(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }

        String[] steps = PropertyPath.split(path);
        JsonObject parent = parentOf(steps, false);

        return parent == null ? null : parent.get(PropertyPath.last(steps));
    }

    /**
     * Walks to the object that owns the last step, optionally creating the objects on the way.
     */
    private JsonObject parentOf(String[] steps, boolean create) {
        JsonObject current = root;

        for (int step = 0; step < steps.length - 1; step++) {
            JsonElement next = current.get(steps[step]);

            if (next == null || !next.isJsonObject()) {

                if (!create) {
                    return null;
                }

                next = new JsonObject();
                current.add(steps[step], next);
            }

            current = next.getAsJsonObject();
        }

        return current;
    }

    private static JsonElement toElement(Object value) {
        return switch (value) {
            case null -> JsonNull.INSTANCE;
            case String text -> new JsonPrimitive(text);
            case Number number -> new JsonPrimitive(number);
            case Boolean flag -> new JsonPrimitive(flag);
            case Character character -> new JsonPrimitive(character);
            default -> throw new IllegalArgumentException(
                "Unsupported migration value type " + value.getClass().getName());
        };
    }
}
