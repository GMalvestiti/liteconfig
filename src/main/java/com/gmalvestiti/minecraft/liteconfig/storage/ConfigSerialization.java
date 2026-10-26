package com.gmalvestiti.minecraft.liteconfig.storage;

import com.gmalvestiti.minecraft.liteconfig.api.annotations.Entry;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Ignore;
import com.gmalvestiti.minecraft.liteconfig.api.LiteConfig;
import com.gmalvestiti.minecraft.liteconfig.registry.ConfigCodecRegistry;
import com.google.gson.ExclusionStrategy;
import com.google.gson.FieldAttributes;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;

import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.lang.ref.WeakReference;

public final class ConfigSerialization {

    private static final ExclusionStrategy CONFIG_IGNORE = new ExclusionStrategy() {

        @Override
        public boolean shouldSkipField(FieldAttributes fieldAttributes) {
            return fieldAttributes.getAnnotation(Ignore.class) != null;
        }

        @Override
        public boolean shouldSkipClass(Class<?> clazz) {
            return false;
        }
    };


    private static volatile Bound bound;

    private ConfigSerialization() {}

    public static String propertyNameOf(Field field) {
        Entry entry = field.getAnnotation(Entry.class);
        return entry == null || entry.name().isBlank() ? field.getName() : entry.name();
    }

    public static JsonElement toTree(Object data) {
        return gson().toJsonTree(data);
    }

    public static <V> V fromTree(JsonElement tree, Class<V> type) {
        return gson().fromJson(tree, type);
    }

    public static <V> V copy(Object source, Type type) {
        Gson gson = gson();
        return gson.fromJson(gson.toJsonTree(source, type), type);
    }

    private static Gson gson() {
        ConfigCodecRegistry codecs = LiteConfig.codecs();

        int generation = codecs.generation();
        Bound currentBound = bound;

        if (currentBound != null && currentBound.generation() == generation) {
            Gson current = currentBound.gson().get();

            if (current != null) {
                return current;
            }
        }

        return bind(codecs);
    }

    private static synchronized Gson bind(ConfigCodecRegistry codecs) {
        int generation = codecs.generation();

        Bound currentBound = bound;

        if (currentBound != null && currentBound.generation() == generation) {
            Gson current = currentBound.gson().get();

            if (current != null) {
                return current;
            }
        }

        Gson gson = codecs.configure(new GsonBuilder()
                .registerTypeAdapterFactory(new ConfigTypeAdapterFactory()))
            .setFieldNamingStrategy(ConfigSerialization::propertyNameOf)
            .addSerializationExclusionStrategy(CONFIG_IGNORE)
            .addDeserializationExclusionStrategy(CONFIG_IGNORE)
            .disableHtmlEscaping()
            .serializeNulls()
            .create();

        bound = new Bound(generation, new WeakReference<>(gson));

        return gson;
    }

    private record Bound(int generation, WeakReference<Gson> gson) {}
}
