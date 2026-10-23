package com.gmalvestiti.minecraft.liteconfig.storage;

import com.gmalvestiti.minecraft.liteconfig.api.LiteConfig;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Entry;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Ignore;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.Codec;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class ConfigSerializationTest {

    @Test
    void testUsesTheSameFieldNamesAndIgnoreRulesInBothDirections() {
        JsonObject tree = ConfigSerialization.toTree(new NamedValues()).getAsJsonObject();
        assertEquals("default", tree.get("display-name").getAsString());
        assertFalse(tree.has("name"));
        assertFalse(tree.has("ignored"));
        tree.addProperty("display-name", "loaded");
        tree.addProperty("ignored", 99);

        NamedValues decoded = ConfigSerialization.fromTree(tree, NamedValues.class);

        assertEquals("loaded", decoded.name);
        assertEquals(7, decoded.ignored);
    }

    @Test
    void testCopyIsolatesNestedMutableValues() {
        NamedValues source = new NamedValues();

        NamedValues copy = ConfigSerialization.copy(source, NamedValues.class);
        copy.values.add("changed");

        assertNotSame(source, copy);
        assertNotSame(source.values, copy.values);
        assertEquals(List.of("original"), source.values);
    }

    @Test
    void testRebuildsSerializationWhenAFileCodecIsRegistered() {
        FileValue value = new FileValue(17);
        assertEquals(17, ConfigSerialization.toTree(value).getAsJsonObject().get("value").getAsInt());
        LiteConfig.codecs().registerCodec(FileValue.class, Codec.INT.xmap(FileValue::new, FileValue::value));

        assertEquals(new JsonPrimitive(17), ConfigSerialization.toTree(value));
        assertEquals(value, ConfigSerialization.fromTree(new JsonPrimitive(17), FileValue.class));
        assertEquals(value, ConfigSerialization.copy(value, FileValue.class));
    }

    static class NamedValues {
        @Entry(name = "display-name")
        String name = "default";
        @Ignore
        int ignored = 7;
        List<String> values = new ArrayList<>(List.of("original"));
    }

    record FileValue(int value) {}
}
