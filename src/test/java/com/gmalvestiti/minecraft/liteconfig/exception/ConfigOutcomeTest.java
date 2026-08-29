package com.gmalvestiti.minecraft.liteconfig.exception;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class ConfigOutcomeTest {

    @Test
    void testReusesEmptyCompletedOutcome() {
        assertSame(ConfigOutcome.completed(null), ConfigOutcome.completed(null));
    }

    @Test
    void testUsesFallbackWithoutOptionalIntermediates() {
        assertEquals("value", ConfigOutcome.completed("value").valueOr(() -> "fallback"));
        assertEquals("fallback", ConfigOutcome.<String>completed(null).valueOr(() -> "fallback"));
    }
}
