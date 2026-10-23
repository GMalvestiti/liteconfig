package com.gmalvestiti.minecraft.liteconfig;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class LiteConfigCommonTest {

    @Test
    void testExposesModId() {
        assertEquals("liteconfig", LiteConfigCommon.MOD_ID);
    }

    @Test
    void testLogsErrorsAsWellAsExceptions() {
        assertDoesNotThrow(() -> LiteConfigCommon.error("fatal", new AssertionError("boom")));
    }
}
