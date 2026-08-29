package com.gmalvestiti.minecraft.liteconfig.api.spi;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ViolationTest {

    @Test
    void testOfCarriesTheIdAndMessage() {
        Violation violation = Violation.of("max-items.range", "maxItems must be 1..64, was 99");

        assertEquals("max-items.range", violation.id());
        assertEquals("maxItems must be 1..64, was 99", violation.message());
        assertInstanceOf(Violation.Default.class, violation);
    }

    @Test
    void testOfIsAValueSoEqualViolationsCompareEqual() {
        assertEquals(Violation.of("rule", "broken"), Violation.of("rule", "broken"));
        assertEquals(
            List.of(Violation.of("a", "x"), Violation.of("b", "y")),
            List.of(Violation.of("a", "x"), Violation.of("b", "y")));
    }

    @Test
    void testOfRejectsTheInputsTheRunnerWouldReportAsADefect() {
        assertThrows(NullPointerException.class, () -> Violation.of(null, "broken"));
        assertThrows(NullPointerException.class, () -> Violation.of("rule", null));
        assertThrows(IllegalArgumentException.class, () -> Violation.of("", "broken"));
        assertThrows(IllegalArgumentException.class, () -> Violation.of("   ", "broken"));
    }
}

