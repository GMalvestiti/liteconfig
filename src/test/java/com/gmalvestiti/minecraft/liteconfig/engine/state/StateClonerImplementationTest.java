package com.gmalvestiti.minecraft.liteconfig.engine.state;

import com.gmalvestiti.minecraft.liteconfig.support.TestFixtures;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;

class StateClonerImplementationTest {

    @Test
    void testDeepCopiesViaJson() {
        StateClonerImplementation cloner = new StateClonerImplementation();
        TestFixtures.SimpleConfig source = new TestFixtures.SimpleConfig();
        source.value = 99;

        TestFixtures.SimpleConfig copy =
            assertInstanceOf(TestFixtures.SimpleConfig.class, cloner.copy(source));
        assertNotNull(copy);
        assertNotSame(source, copy);
        assertEquals(99, copy.value);
    }

    @Test
    void testReturnsNullForNullInput() {
        StateClonerImplementation cloner = new StateClonerImplementation();
        assertNull(cloner.copy(null));
    }
}
