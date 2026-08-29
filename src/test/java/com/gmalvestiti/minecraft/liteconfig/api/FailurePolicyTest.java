package com.gmalvestiti.minecraft.liteconfig.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FailurePolicyTest {

    @Test
    void testExposesExpectedValues() {
        assertEquals(FailurePolicy.STRICT, FailurePolicy.valueOf("STRICT"));
        assertEquals(FailurePolicy.FALLBACK, FailurePolicy.valueOf("FALLBACK"));
        assertEquals(2, FailurePolicy.values().length);
    }
}

