package com.gmalvestiti.minecraft.liteconfig.api.annotations;

import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ConfigTest {

    @Test
    void testHasRuntimeTypeTarget() {
        Retention retention = Config.class.getAnnotation(Retention.class);
        Target target = Config.class.getAnnotation(Target.class);
        assertEquals(RetentionPolicy.RUNTIME, retention.value());
        assertArrayEquals(new ElementType[]{ElementType.TYPE}, target.value());
    }
}

