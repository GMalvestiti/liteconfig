package com.gmalvestiti.minecraft.liteconfig.reflection;

import com.gmalvestiti.minecraft.liteconfig.exception.LiteConfigException;
import com.gmalvestiti.minecraft.liteconfig.support.TestFixtures;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigObjectFactoryTest {

    @Test
    void testCreatesInstanceWhenNoArgConstructorExists() {
        TestFixtures.SimpleConfig instance = ConfigObjectFactory.newInstance(TestFixtures.SimpleConfig.class, TestFixtures.SCOPE);
        assertNotNull(instance);
    }

    @Test
    void testThrowsWhenNoDefaultConstructorExists() {
        assertThrows(
            LiteConfigException.class,
            () -> ConfigObjectFactory.newInstance(TestFixtures.NoDefaultConstructorConfig.class, TestFixtures.SCOPE)
        );
    }

    @Test
    void testWrapsConstructorInvocationFailure() {
        LiteConfigException failure = assertThrows(
            LiteConfigException.class,
            () -> ConfigObjectFactory.newInstance(TestFixtures.ThrowingConstructorConfig.class, TestFixtures.SCOPE)
        );

        assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertEquals("boom", failure.getCause().getMessage());
        assertTrue(failure.getMessage().contains("boom"));
    }
}
