package com.gmalvestiti.minecraft.liteconfig.shared;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PropertyPathTest {

    @Test
    void testSplitsAPropertyPath() {
        assertArrayEquals(new String[] {"display", "hud", "scale"},
            PropertyPath.split("display.hud.scale"));
    }

    @Test
    void testRejectsMissingOrBlankPaths() {
        assertThrows(NullPointerException.class, () -> PropertyPath.split(null));
        assertThrows(IllegalArgumentException.class, () -> PropertyPath.split(""));
        assertThrows(IllegalArgumentException.class, () -> PropertyPath.split(" "));
    }

    @Test
    void testRejectsBlankPathSegments() {
        assertThrows(IllegalArgumentException.class, () -> PropertyPath.split(".display"));
        assertThrows(IllegalArgumentException.class, () -> PropertyPath.split("display."));
        assertThrows(IllegalArgumentException.class, () -> PropertyPath.split("display..scale"));
        assertThrows(IllegalArgumentException.class, () -> PropertyPath.split("display. .scale"));
    }

    @Test
    void testBuildsRootAndNestedChildren() {
        assertEquals("display", PropertyPath.child(null, "display"));
        assertEquals("display", PropertyPath.child("", "display"));
        assertEquals("display.hud", PropertyPath.child("display", "hud"));
    }

    @Test
    void testRejectsInvalidChildren() {
        assertThrows(NullPointerException.class, () -> PropertyPath.child(null, null));
        assertThrows(IllegalArgumentException.class, () -> PropertyPath.child(null, ""));
        assertThrows(IllegalArgumentException.class, () -> PropertyPath.child(null, " "));
        assertThrows(IllegalArgumentException.class, () -> PropertyPath.child(null, "display.hud"));
        assertThrows(IllegalArgumentException.class, () -> PropertyPath.child("display.", "hud"));
    }

    @Test
    void testReturnsTheLastPathStep() {
        assertEquals("scale", PropertyPath.last(new String[] {"display", "hud", "scale"}));
    }

    @Test
    void testRejectsMissingOrBlankLastSteps() {
        assertThrows(NullPointerException.class, () -> PropertyPath.last(null));
        assertThrows(IllegalArgumentException.class, () -> PropertyPath.last(new String[0]));
        assertThrows(NullPointerException.class, () -> PropertyPath.last(new String[] {null}));
        assertThrows(IllegalArgumentException.class, () -> PropertyPath.last(new String[] {" "}));
    }
}
