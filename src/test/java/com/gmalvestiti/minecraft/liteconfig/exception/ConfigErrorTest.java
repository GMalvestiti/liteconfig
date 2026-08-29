package com.gmalvestiti.minecraft.liteconfig.exception;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigErrorTest {

    @Test
    void testExposesRawTemplatesWithoutFormatting() {
        assertEquals("Config %s declares invalid name '%s'", ConfigError.INVALID_CONFIG_NAME.template());
    }

    @Test
    void testMarksOnlyDefectsAsDefects() {
        assertFalse(ConfigError.INVALID_CONFIG_NAME.defect());
        assertTrue(ConfigError.UNEXPECTED_FAILURE.defect());
    }
}

