package com.gmalvestiti.minecraft.liteconfig.migration;

import com.gmalvestiti.minecraft.liteconfig.api.migration.ConfigData;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonConfigDataTest {

    private final JsonObject tree = JsonParser
        .parseString("{\"hudScale\":3,\"name\":\"steve\",\"enabled\":true,\"hud\":{\"colour\":\"red\"}}")
        .getAsJsonObject();
    private final ConfigData data = new JsonConfigData(tree);

    @Test
    void testFindsValuesByDottedPath() {
        assertEquals("red", data.string("hud.colour").orElseThrow());
        assertEquals(3, data.number("hudScale").orElseThrow().intValue());
        assertTrue(data.bool("enabled").orElseThrow());
    }

    @Test
    void testReportsWhetherAPathExists() {
        assertTrue(data.has("hud.colour"));
        assertFalse(data.has("hud.missing"));
        assertFalse(data.has("missing.entirely"));
    }

    @Test
    void testReturnsEmptyWhenTheValueIsOfAnotherKind() {
        assertTrue(data.number("name").isEmpty());
        assertTrue(data.bool("hudScale").isEmpty());
        assertTrue(data.string("hudScale").isEmpty());
        assertTrue(data.string("enabled").isEmpty());
        assertTrue(data.string("absent").isEmpty());
    }

    @Test
    void testFallsBackWhenTheFileHasNothingUsable() {
        assertEquals("none", data.string("absent", "none"));
        assertEquals(9, data.integer("absent", 9));
        assertEquals(1.5, data.decimal("name", 1.5));
        assertFalse(data.bool("hudScale", false));
    }

    @Test
    void testReadsWithFallbackWhenTheFileHasTheValue() {
        assertEquals("steve", data.string("name", "none"));
        assertEquals(3, data.integer("hudScale", 9));
        assertEquals(3.0, data.decimal("hudScale", 1.5));
        assertTrue(data.bool("enabled", false));
    }

    @Test
    void testCreatesTheObjectsAlongTheWayWhenSetting() {
        data.set("display.hud.scale", 8);

        assertEquals(8, data.number("display.hud.scale").orElseThrow().intValue());
    }

    @Test
    void testChainsEdits() {
        assertSame(data, data.set("name", "alex").remove("enabled").rename("hudScale", "hud.scale"));

        assertEquals("alex", data.string("name").orElseThrow());
        assertFalse(data.has("enabled"));
        assertEquals(3, data.integer("hud.scale", 0));
    }

    @Test
    void testRemovesWithoutComplainingAboutAbsentPaths() {
        data.remove("name").remove("name").remove("hud.missing").remove("missing.entirely");

        assertFalse(data.has("name"));
    }

    @Test
    void testLeavesTheTreeAloneWhenTheRenameSourceIsAbsent() {
        data.rename("missing", "hud.scale");

        assertFalse(data.has("hud.scale"));
    }

    @Test
    void testRejectsValueTypesItCannotStore() {
        assertThrows(IllegalArgumentException.class, () -> data.set("name", new Object()));
    }

    @Test
    void testRejectsABlankPath() {
        assertThrows(IllegalArgumentException.class, () -> data.set("", 1));
        assertThrows(IllegalArgumentException.class, () -> data.rename("name", " "));
    }
}
