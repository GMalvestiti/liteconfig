package com.gmalvestiti.minecraft.liteconfig.api.annotations;

import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigTest {

    @Test
    void testReadOnlyIsOptInOnTheConfigClass() {
        assertFalse(MutableConfig.class.getAnnotation(Config.class).readOnly());
        assertTrue(ReadOnlyConfig.class.getAnnotation(Config.class).readOnly());
    }

    @Test
    void testHasRuntimeTypeTarget() {
        Retention retention = Config.class.getAnnotation(Retention.class);
        Target target = Config.class.getAnnotation(Target.class);
        assertEquals(RetentionPolicy.RUNTIME, retention.value());
        assertArrayEquals(new ElementType[]{ElementType.TYPE}, target.value());
    }

    @Config(name = "mutable")
    static class MutableConfig {}

    @Config(name = "read-only", readOnly = true)
    static class ReadOnlyConfig {}
}
